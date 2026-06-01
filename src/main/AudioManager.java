package main;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_decode_filename;
import static org.lwjgl.system.MemoryStack.stackPush;

public class AudioManager {
    private static long device;
    private static long context;
    private static final Map<String, Integer> soundBuffers = new HashMap<>();
    private static final int MAX_SOURCES = 16;
    private static final int[] sources = new int[MAX_SOURCES];
    private static int nextSource = 0;

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

        for (int i = 0; i < MAX_SOURCES; i++) {
            sources[i] = alGenSources();
        }
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
                IntBuffer error = stack.mallocInt(1);

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
        Integer buffer = soundBuffers.get(name);
        if (buffer == null) return;

        int source = sources[nextSource];
        alSourceStop(source);
        alSourcei(source, AL_BUFFER, buffer);
        alSourcePlay(source);

        nextSource = (nextSource + 1) % MAX_SOURCES;
    }

    public static void cleanup() {
        for (int source : sources) {
            alDeleteSources(source);
        }
        for (int buffer : soundBuffers.values()) {
            alDeleteBuffers(buffer);
        }
        alcMakeContextCurrent(MemoryUtil.NULL);
        alcDestroyContext(context);
        alcCloseDevice(device);
    }
}
