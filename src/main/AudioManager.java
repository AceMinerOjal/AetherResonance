package main;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class AudioManager implements AudioBus {
  private static final int POOL_MIN = 16;
  private static final int POOL_MAX = 64;
  private static final float MIN_PITCH = 0.9f;
  private static final float MAX_PITCH = 1.1f;
  private static final float FADE_STEP = 0.05f;

  private long device;
  private long context;
  private boolean ready;

  private final Map<String, Integer> soundBuffers = new HashMap<>();
  private final Deque<ManagedSource> freeSources = new ArrayDeque<>();
  private int totalSources;

  private float masterVolume = 1.0f;
  private float sfxVolume = 1.0f;
  private float musicVolume = 1.0f;

  private String currentMusic;
  AudioStream musicStream;
  private float musicFadeTarget = 1.0f;
  private float musicFadeCurrent = 1.0f;
  private boolean musicFading;

  public boolean init() {
    device = alcOpenDevice((java.nio.ByteBuffer) null);
    if (device == MemoryUtil.NULL) {
      System.err.println("AudioManager: failed to open OpenAL device.");
      return false;
    }

    ALCCapabilities alcCapabilities = ALC.createCapabilities(device);
    context = alcCreateContext(device, (IntBuffer) null);
    if (context == MemoryUtil.NULL) {
      System.err.println("AudioManager: failed to create OpenAL context.");
      alcCloseDevice(device);
      device = MemoryUtil.NULL;
      return false;
    }

    alcMakeContextCurrent(context);
    AL.createCapabilities(alcCapabilities);

    alDistanceModel(AL_INVERSE_DISTANCE_CLAMPED);

    for (int i = 0; i < POOL_MIN; i++) {
      freeSources.add(createSource());
    }
    totalSources = POOL_MIN;

    ready = true;
    return true;
  }

  @Override
  public void setListenerData(float x, float y, float z) {
    if (!ready) return;
    alListener3f(AL_POSITION, x, y, z);
    alListener3f(AL_VELOCITY, 0, 0, 0);
  }

  public boolean loadSound(String name, String resourcePath) {
    if (soundBuffers.containsKey(name)) return true;

    try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        System.err.println("AudioManager: could not find sound resource: " + resourcePath);
        return false;
      }

      byte[] bytes = in.readAllBytes();
      java.nio.ByteBuffer vorbisBuffer = MemoryUtil.memAlloc(bytes.length);
      vorbisBuffer.put(bytes);
      vorbisBuffer.flip();

      try (MemoryStack stack = stackPush()) {
        IntBuffer channelsBuffer = stack.mallocInt(1);
        IntBuffer sampleRateBuffer = stack.mallocInt(1);

        ShortBuffer rawAudioBuffer = STBVorbis.stb_vorbis_decode_memory(vorbisBuffer, channelsBuffer, sampleRateBuffer);
        if (rawAudioBuffer == null) {
          System.err.println("AudioManager: failed to decode sound: " + resourcePath);
          MemoryUtil.memFree(vorbisBuffer);
          return false;
        }

        int channels = channelsBuffer.get();
        int sampleRate = sampleRateBuffer.get();
        int format = (channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

        int buffer = alGenBuffers();
        alBufferData(buffer, format, rawAudioBuffer, sampleRate);
        soundBuffers.put(name, buffer);

        MemoryUtil.memFree(rawAudioBuffer);
        return true;
      } finally {
        MemoryUtil.memFree(vorbisBuffer);
      }
    } catch (java.io.IOException e) {
      System.err.println("AudioManager: error reading sound resource: " + resourcePath + " - " + e.getMessage());
      return false;
    }
  }

  @Override
  public void playSound(String name, SoundCategory category, float x, float y, float z, boolean loop) {
    if (!ready) return;
    Integer buffer = soundBuffers.get(name);
    if (buffer == null) return;

    ManagedSource source = acquireSource();
    if (source == null) return;

    source.category = category;
    alSourcei(source.id, AL_SOURCE_RELATIVE, AL_FALSE);
    alSourcei(source.id, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
    alSource3f(source.id, AL_POSITION, x, y, z);
    alSourcef(source.id, AL_GAIN, gainForCategory(category) * masterVolume);
    alSourcef(source.id, AL_PITCH, randomPitch());
    alSourcei(source.id, AL_BUFFER, buffer);
    alSourcePlay(source.id);
  }

  @Override
  public void playSound(String name, SoundCategory category) {
    if (!ready) return;
    Integer buffer = soundBuffers.get(name);
    if (buffer == null) return;

    ManagedSource source = acquireSource();
    if (source == null) return;

    source.category = category;
    alSourcei(source.id, AL_SOURCE_RELATIVE, AL_TRUE);
    alSourcei(source.id, AL_LOOPING, AL_FALSE);
    alSourcef(source.id, AL_GAIN, gainForCategory(category) * masterVolume);
    alSourcef(source.id, AL_PITCH, randomPitch());
    alSourcei(source.id, AL_BUFFER, buffer);
    alSourcePlay(source.id);
  }

  @Override
  public void playMusic(String name, boolean loop, boolean fadeIn) {
    if (!ready) return;
    if (name.equals(currentMusic) && musicStream != null && !musicStream.isStopped()) return;

    stopMusic(true);

    currentMusic = name;
    AudioStream stream = new AudioStream(name, loop);
    if (!stream.open()) {
      currentMusic = null;
      return;
    }

    musicStream = stream;
    musicStream.play();

    if (fadeIn) {
      musicFadeCurrent = 0.0f;
      musicFadeTarget = musicVolume * masterVolume;
      musicFading = true;
    } else {
      musicFadeCurrent = musicVolume * masterVolume;
      musicFadeTarget = musicFadeCurrent;
      musicFading = false;
      musicStream.setGain(musicFadeCurrent);
    }
  }

  @Override
  public void stopMusic(boolean fadeOut) {
    if (musicStream == null) return;

    if (fadeOut) {
      musicFadeTarget = 0.0f;
      musicFading = true;
      Thread fadeThread = new Thread(() -> {
        try {
          while (musicFading) {
            Thread.sleep(16);
          }
        } catch (InterruptedException ignored) {}
        doStopMusic();
      }, "MusicFadeOut");
      fadeThread.setDaemon(true);
      fadeThread.start();
    } else {
      doStopMusic();
    }
  }

  private void doStopMusic() {
    if (musicStream != null) {
      musicStream.cleanup();
      musicStream = null;
    }
    currentMusic = null;
    musicFading = false;
  }

  public void updateMusicFade() {
    if (!musicFading || musicStream == null) return;

    float step = FADE_STEP * masterVolume;
    if (musicFadeCurrent < musicFadeTarget) {
      musicFadeCurrent = Math.min(musicFadeTarget, musicFadeCurrent + step);
    } else if (musicFadeCurrent > musicFadeTarget) {
      musicFadeCurrent = Math.max(musicFadeTarget, musicFadeCurrent - step);
    }

    if (Math.abs(musicFadeCurrent - musicFadeTarget) < 0.005f) {
      musicFadeCurrent = musicFadeTarget;
      if (musicFadeTarget <= 0.0f && musicStream != null) {
        doStopMusic();
        return;
      }
      musicFading = false;
    }

    if (musicStream != null) {
      musicStream.setGain(musicFadeCurrent);
    }
  }

  @Override
  public void setMasterVolume(float volume) {
    masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
  }

  @Override
  public void setSfxVolume(float volume) {
    sfxVolume = Math.max(0.0f, Math.min(1.0f, volume));
  }

  @Override
  public void setMusicVolume(float volume) {
    musicVolume = Math.max(0.0f, Math.min(1.0f, volume));
    if (musicStream != null && !musicFading) {
      musicStream.setGain(musicVolume * masterVolume);
    }
  }

  private ManagedSource acquireSource() {
    if (!freeSources.isEmpty()) {
      return freeSources.pollFirst();
    }
    if (totalSources < POOL_MAX) {
      totalSources++;
      ManagedSource source = createSource();
      return source;
    }
    return null;
  }

  private void recycleSource(ManagedSource source) {
    alSourceStop(source.id);
    alSourcei(source.id, AL_BUFFER, 0);
    freeSources.addLast(source);
  }

  private ManagedSource createSource() {
    int id = alGenSources();
    alSourcef(id, AL_REFERENCE_DISTANCE, 64.0f);
    alSourcef(id, AL_ROLLOFF_FACTOR, 1.5f);
    alSourcef(id, AL_MAX_DISTANCE, 512.0f);
    return new ManagedSource(id);
  }

  public void cleanup() {
    if (!ready) return;
    doStopMusic();
    for (ManagedSource source : freeSources) {
      alSourceStop(source.id);
      alDeleteSources(source.id);
    }
    freeSources.clear();
    for (int buffer : soundBuffers.values()) {
      alDeleteBuffers(buffer);
    }
    soundBuffers.clear();
    alcMakeContextCurrent(MemoryUtil.NULL);
    if (context != MemoryUtil.NULL) {
      alcDestroyContext(context);
    }
    if (device != MemoryUtil.NULL) {
      alcCloseDevice(device);
    }
    ready = false;
  }

  private float gainForCategory(SoundCategory category) {
    return switch (category) {
      case SFX, AMBIENT -> sfxVolume;
      case MUSIC -> musicVolume;
      case UI -> 1.0f;
    };
  }

  private float randomPitch() {
    return MIN_PITCH + ThreadLocalRandom.current().nextFloat() * (MAX_PITCH - MIN_PITCH);
  }

  private static class ManagedSource {
    final int id;
    SoundCategory category;

    ManagedSource(int id) {
      this.id = id;
      this.category = SoundCategory.SFX;
    }
  }
}
