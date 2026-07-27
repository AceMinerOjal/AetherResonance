package main;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.nio.ByteBuffer;

import static org.lwjgl.openal.AL10.*;

public class AudioStream {
  private static final int BUFFER_COUNT = 4;
  private static final int SAMPLES_PER_CHUNK = 16384;

  private final int[] buffers = new int[BUFFER_COUNT];
  private final int sourceId;
  private final String resourcePath;
  private final boolean loop;
  private long vorbisHandle;
  private int channels;
  private int sampleRate;
  private int format;
  private volatile boolean running;
  private volatile boolean stopped;
  private volatile boolean finished;
  volatile Runnable onFinish;
  private Thread streamThread;

  public AudioStream(String resourcePath, boolean loop) {
    this.sourceId = alGenSources();
    this.resourcePath = resourcePath;
    this.loop = loop;
    for (int i = 0; i < BUFFER_COUNT; i++) {
      buffers[i] = alGenBuffers();
    }
  }

  public boolean open() {
    try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        System.err.println("AudioStream: resource not found: " + resourcePath);
        return false;
      }
      byte[] bytes = in.readAllBytes();
      ByteBuffer vorbisBuffer = MemoryUtil.memAlloc(bytes.length);
      vorbisBuffer.put(bytes).flip();

      int[] error = new int[1];
      vorbisHandle = STBVorbis.stb_vorbis_open_memory(vorbisBuffer, error, null);
      if (vorbisHandle == 0) {
        System.err.println("AudioStream: failed to decode: " + resourcePath);
        MemoryUtil.memFree(vorbisBuffer);
        return false;
      }

      try (STBVorbisInfo info = STBVorbisInfo.malloc()) {
        STBVorbis.stb_vorbis_get_info(vorbisHandle, info);
        channels = info.channels();
        sampleRate = info.sample_rate();
      }
      format = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

      fillAllBuffers();
      alSourceQueueBuffers(sourceId, buffers);

      running = true;
      streamThread = new Thread(this::streamLoop, "AudioStream-" + resourcePath);
      streamThread.setDaemon(true);
      streamThread.start();
      return true;
    } catch (Exception e) {
      System.err.println("AudioStream: error opening " + resourcePath + ": " + e.getMessage());
      return false;
    }
  }

  public void play() {
    if (!running) return;
    alSourcePlay(sourceId);
  }

  public void stop() {
    stopped = true;
    running = false;
    alSourceStop(sourceId);
    alSourceUnqueueBuffers(sourceId);
    if (vorbisHandle != 0) {
      STBVorbis.stb_vorbis_close(vorbisHandle);
      vorbisHandle = 0;
    }
  }

  public boolean isStopped() {
    return stopped;
  }

  public boolean isFinished() {
    return finished;
  }

  public void setGain(float gain) {
    alSourcef(sourceId, AL_GAIN, gain);
  }

  private void fillAllBuffers() {
    for (int i = 0; i < BUFFER_COUNT; i++) {
      fillBuffer(buffers[i]);
    }
  }

  private void fillBuffer(int buffer) {
    ShortBuffer samples = MemoryUtil.memAllocShort(SAMPLES_PER_CHUNK);
    int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(
        vorbisHandle, channels, samples);

    if (samplesRead == 0 && loop) {
      STBVorbis.stb_vorbis_seek_start(vorbisHandle);
      samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(
          vorbisHandle, channels, samples);
    }

    if (samplesRead > 0) {
      samples.limit(samplesRead * channels);
      alBufferData(buffer, format, samples, sampleRate);
      MemoryUtil.memFree(samples);
      return;
    }
    MemoryUtil.memFree(samples);
    eof = true;
  }

  private volatile boolean eof;

  private void streamLoop() {
    try {
      while (running && !stopped) {
        int processed = alGetSourcei(sourceId, AL_BUFFERS_PROCESSED);

        if (eof) {
          int queued = alGetSourcei(sourceId, AL_BUFFERS_QUEUED);
          if (processed >= queued && queued > 0) {
            finished = true;
            Runnable cb = onFinish;
            if (cb != null) cb.run();
            stopped = true;
            break;
          }
        }

        for (int i = 0; i < processed; i++) {
          int buf = alSourceUnqueueBuffers(sourceId);
          fillBuffer(buf);
          alSourceQueueBuffers(sourceId, buf);
        }

        if (alGetSourcei(sourceId, AL_SOURCE_STATE) != AL_PLAYING && !stopped && !eof) {
          alSourcePlay(sourceId);
        }

        Thread.sleep(10);
      }
    } catch (InterruptedException ignored) {
    } finally {
      stopped = true;
    }
  }

  public void cleanup() {
    stop();
    try { streamThread.join(1000); } catch (InterruptedException ignored) {}
    for (int buf : buffers) {
      alDeleteBuffers(buf);
    }
    alDeleteSources(sourceId);
  }
}
