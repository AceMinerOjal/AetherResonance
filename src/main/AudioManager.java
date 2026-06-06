package main;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class AudioManager {
    private static long device;
    private static long context;

    private static final Map<String, Integer> soundBuffers = new HashMap<>();
    private static final List<AudioSource> sources = new ArrayList<>();
    
    private static float masterVolume = 1.0f;
    private static float sfxVolume = 1.0f;
    private static float musicVolume = 1.0f;

    public static void init() {
        device = alcOpenDevice((java.nio.ByteBuffer) null);
        if (device == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to open OpenAL device.");
        }

        ALCCapabilities alcCapabilities = ALC.createCapabilities(device);
        context = alcCreateContext(device, (IntBuffer) null);
        if (context == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to create OpenAL context.");
        }

        alcMakeContextCurrent(context);
        AL.createCapabilities(alcCapabilities);

        // Initialize a pool of sources
        for (int i = 0; i < 16; i++) {
            sources.add(new AudioSource(false, false));
        }
    }

    public static void setListenerData(float x, float y, float z) {
        alListener3f(AL_POSITION, x, y, z);
        alListener3f(AL_VELOCITY, 0, 0, 0);
    }

    public static void loadSound(String name, String resourcePath) {
        if (soundBuffers.containsKey(name)) return;

        try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("Could not find sound resource: " + resourcePath);
                return;
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
                    System.err.println("Failed to decode sound: " + resourcePath);
                    MemoryUtil.memFree(vorbisBuffer);
                    return;
                }

                int channels = channelsBuffer.get();
                int sampleRate = sampleRateBuffer.get();
                int format = (channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

                int buffer = alGenBuffers();
                alBufferData(buffer, format, rawAudioBuffer, sampleRate);
                soundBuffers.put(name, buffer);

                MemoryUtil.memFree(rawAudioBuffer);
            } finally {
                MemoryUtil.memFree(vorbisBuffer);
            }
        } catch (java.io.IOException e) {
            System.err.println("Error reading sound resource: " + resourcePath + " - " + e.getMessage());
        }
    }

    public static void playSound(String name) {
        playSound(name, 0, 0, 0, false);
    }

    public static void playStaticSound(String name) {
        Integer buffer = soundBuffers.get(name);
        if (buffer == null) return;

        AudioSource source = getFreeSource();
        if (source != null) {
            source.setMusic(false);
            source.setRelative(true); // Always centered
            source.setLooping(false);
            source.setGain(sfxVolume * masterVolume);
            source.play(buffer);
        }
    }

    public static void playSound(String name, float x, float y, float z, boolean loop) {
        Integer buffer = soundBuffers.get(name);
        if (buffer == null) return;

        AudioSource source = getFreeSource();
        if (source != null) {
            source.setMusic(false);
            source.setRelative(false);
            source.setLooping(loop);
            source.setPosition(x, y, z);
            source.setGain(sfxVolume * masterVolume);
            source.play(buffer);
        }
    }

    public static void playMusic(String name, boolean loop) {
        Integer buffer = soundBuffers.get(name);
        if (buffer == null) return;

        // For music, we often want a dedicated source or a specific one from the pool
        // Simple implementation for now: use a source but mark it as music
        AudioSource source = getFreeSource();
        if (source != null) {
            source.setMusic(true);
            source.setRelative(true); // Music usually isn't positional
            source.setLooping(loop);
            source.setGain(musicVolume * masterVolume);
            source.play(buffer);
        }
    }

    private static AudioSource getFreeSource() {
        for (AudioSource source : sources) {
            if (!source.isPlaying()) {
                return source;
            }
        }
        // If all are busy, just grab the first one (simple replacement)
        return sources.get(0);
    }

    public static void setMasterVolume(float volume) {
        masterVolume = volume;
        updateAllSourcesVolume();
    }

    public static void setSfxVolume(float volume) {
        sfxVolume = volume;
        updateAllSourcesVolume();
    }

    public static void setMusicVolume(float volume) {
        musicVolume = volume;
        updateAllSourcesVolume();
    }

    private static void updateAllSourcesVolume() {
        for (AudioSource source : sources) {
            float baseVolume = source.isMusic() ? musicVolume : sfxVolume;
            source.setGain(baseVolume * masterVolume);
        }
    }

    public static void cleanup() {
        for (AudioSource source : sources) {
            source.cleanup();
        }
        for (int buffer : soundBuffers.values()) {
            alDeleteBuffers(buffer);
        }
        alcMakeContextCurrent(MemoryUtil.NULL);
        alcDestroyContext(context);
        alcCloseDevice(device);
    }

    public static class AudioSource {
        private final int sourceId;
        private boolean isMusic = false;

        public AudioSource(boolean loop, boolean relative) {
            this.sourceId = alGenSources();
            alSourcei(sourceId, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
            alSourcei(sourceId, AL_SOURCE_RELATIVE, relative ? AL_TRUE : AL_FALSE);
        }

        public void play(int buffer) {
            stop();
            alSourcei(sourceId, AL_BUFFER, buffer);
            alSourcePlay(sourceId);
        }

        public void pause() {
            alSourcePause(sourceId);
        }

        public void stop() {
            alSourceStop(sourceId);
        }

        public boolean isPlaying() {
            return alGetSourcei(sourceId, AL_SOURCE_STATE) == AL_PLAYING;
        }

        public void setLooping(boolean loop) {
            alSourcei(sourceId, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
        }

        public void setRelative(boolean relative) {
            alSourcei(sourceId, AL_SOURCE_RELATIVE, relative ? AL_TRUE : AL_FALSE);
        }

        public void setPosition(float x, float y, float z) {
            alSource3f(sourceId, AL_POSITION, x, y, z);
        }

        public void setGain(float gain) {
            alSourcef(sourceId, AL_GAIN, gain);
        }

        public void setMusic(boolean music) {
            this.isMusic = music;
        }

        public boolean isMusic() {
            return isMusic;
        }

        public void cleanup() {
            stop();
            alDeleteSources(sourceId);
        }
    }
}
