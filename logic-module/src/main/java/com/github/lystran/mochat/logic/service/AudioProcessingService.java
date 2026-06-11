package com.github.lystran.mochat.logic.service;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.sound.sampled.*;

@Singleton
public class AudioProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AudioProcessingService.class);
    
    private static final String TARGET_FORMAT = "mp3";
    private static final int TARGET_SAMPLE_RATE = 44100;
    private static final int TARGET_BIT_RATE = 128000;
    private static final int WAVEFORM_POINTS = 100;

    public byte[] transcodeAudio(byte[] audioData, String mimeType) throws IOException {
        if (!isSupportedAudioType(mimeType)) {
            throw new IllegalArgumentException("Unsupported audio type for transcoding: " + mimeType);
        }

        log.info("Starting audio transcoding, mimeType={}, dataSize={} bytes", mimeType, audioData.length);

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(audioData)) {
            AudioInputStream sourceStream = AudioSystem.getAudioInputStream(inputStream);
            
            AudioFormat sourceFormat = sourceStream.getFormat();
            AudioFormat targetFormat = createTargetFormat(sourceFormat);
            
            if (sourceFormat.matches(targetFormat)) {
                log.info("Audio format already matches target, skipping transcoding");
                return audioData;
            }
            
            AudioInputStream transcodedStream = AudioSystem.getAudioInputStream(targetFormat, sourceStream);
            
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = transcodedStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            byte[] result = outputStream.toByteArray();
            log.info("Audio transcoding completed, originalSize={} bytes, transcodedSize={} bytes", 
                    audioData.length, result.length);
            
            return result;
        } catch (UnsupportedAudioFileException e) {
            log.error("Failed to decode audio file, mimeType={}", mimeType, e);
            throw new RuntimeException("Failed to decode audio file", e);
        }
    }

    public String generateWaveformData(byte[] audioData, String mimeType) throws IOException {
        if (!isSupportedAudioType(mimeType)) {
            throw new IllegalArgumentException("Unsupported audio type for waveform generation: " + mimeType);
        }

        log.info("Generating waveform data, mimeType={}, dataSize={} bytes", mimeType, audioData.length);

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(audioData)) {
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(inputStream);
            
            AudioFormat format = audioInputStream.getFormat();
            int frameLength = (int) audioInputStream.getFrameLength();
            
            if (frameLength <= 0) {
                log.warn("Audio frame length is invalid, returning empty waveform");
                return encodeEmptyWaveform();
            }
            
            byte[] audioBytes = readAllBytes(audioInputStream);
            float[] samples = convertToSamples(audioBytes, format);
            
            float[] waveform = downsampleWaveform(samples, WAVEFORM_POINTS);
            
            String encodedWaveform = Base64.getEncoder().encodeToString(floatsToBytes(waveform));
            log.info("Waveform generation completed, points={}, encodedSize={} bytes", 
                    WAVEFORM_POINTS, encodedWaveform.length());
            
            return encodedWaveform;
        } catch (UnsupportedAudioFileException e) {
            log.error("Failed to decode audio file for waveform, mimeType={}", mimeType, e);
            throw new RuntimeException("Failed to decode audio file for waveform", e);
        }
    }

    private AudioFormat createTargetFormat(AudioFormat sourceFormat) {
        AudioFormat.Encoding encoding = AudioFormat.Encoding.PCM_SIGNED;

        return new AudioFormat(
                encoding,
                TARGET_SAMPLE_RATE,
                16,
                2,
                4,
                TARGET_SAMPLE_RATE,
                false
        );
    }

    private boolean isSupportedAudioType(String mimeType) {
        return mimeType != null && (
                mimeType.startsWith("audio/mpeg") ||
                        mimeType.startsWith("audio/wav") ||
                        mimeType.startsWith("audio/ogg") ||
                        mimeType.startsWith("audio/flac") ||
                        mimeType.startsWith("audio/aac") ||
                        mimeType.startsWith("audio/mp4") ||
                        mimeType.equals("audio/x-m4a")
        );
    }

    private byte[] readAllBytes(AudioInputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = stream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private float[] convertToSamples(byte[] audioBytes, AudioFormat format) {
        int bytesPerSample = format.getSampleSizeInBits() / 8;
        int numSamples = audioBytes.length / bytesPerSample;
        float[] samples = new float[numSamples];

        for (int i = 0; i < numSamples; i++) {
            int offset = i * bytesPerSample;
            float sample;

            if (format.isBigEndian()) {
                if (bytesPerSample == 2) {
                    sample = (float) ((audioBytes[offset] << 8) | (audioBytes[offset + 1] & 0xFF));
                } else {
                    sample = (float) audioBytes[offset];
                }
            } else {
                if (bytesPerSample == 2) {
                    sample = (float) ((audioBytes[offset + 1] << 8) | (audioBytes[offset] & 0xFF));
                } else {
                    sample = (float) audioBytes[offset];
                }
            }

            samples[i] = sample / 32768.0f;
        }

        return samples;
    }

    private float[] downsampleWaveform(float[] samples, int targetPoints) {
        if (samples.length == 0) {
            return new float[targetPoints];
        }

        float[] waveform = new float[targetPoints];
        int samplesPerPoint = Math.max(1, samples.length / targetPoints);

        for (int i = 0; i < targetPoints; i++) {
            int startIdx = i * samplesPerPoint;
            int endIdx = Math.min((i + 1) * samplesPerPoint, samples.length);

            float sum = 0;
            int count = 0;
            for (int j = startIdx; j < endIdx; j++) {
                sum += Math.abs(samples[j]);
                count++;
            }

            waveform[i] = count > 0 ? sum / count : 0;
        }

        return waveform;
    }

    private byte[] floatsToBytes(float[] floats) {
        byte[] bytes = new byte[floats.length * 2];
        for (int i = 0; i < floats.length; i++) {
            short s = (short) (floats[i] * Short.MAX_VALUE);
            bytes[i * 2] = (byte) (s & 0xFF);
            bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return bytes;
    }

    private String encodeEmptyWaveform() {
        byte[] emptyWaveform = new byte[WAVEFORM_POINTS * 2];
        return Base64.getEncoder().encodeToString(emptyWaveform);
    }
}
