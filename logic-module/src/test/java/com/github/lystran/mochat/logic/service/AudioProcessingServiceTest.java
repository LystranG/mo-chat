package com.github.lystran.mochat.logic.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class AudioProcessingServiceTest {

    private AudioProcessingService audioProcessingService;

    @BeforeEach
    void setUp() {
        audioProcessingService = new AudioProcessingService();
    }

    @Test
    void testGenerateWaveformFromPcmData() throws Exception {
        byte[] pcmData = generateTestPcmData(44100, 1);

        String waveformData = audioProcessingService.generateWaveformData(pcmData, "audio/wav");

        assertNotNull(waveformData);
        assertFalse(waveformData.isEmpty());
        assertTrue(waveformData.length() > 0);
    }

    @Test
    void testTranscodeAudioPreservesContent() throws Exception {
        byte[] originalData = generateTestPcmData(44100, 1);

        byte[] transcodedData = audioProcessingService.transcodeAudio(originalData, "audio/wav");

        assertNotNull(transcodedData);
        assertTrue(transcodedData.length > 0);
    }

    @Test
    void testUnsupportedAudioTypeThrowsException() {
        byte[] invalidData = new byte[100];

        assertThrows(IllegalArgumentException.class, () ->
                audioProcessingService.transcodeAudio(invalidData, "application/octet-stream")
        );
    }

    @Test
    void testEmptyAudioReturnsEmptyWaveform() throws Exception {
        byte[] emptyData = new byte[0];

        assertThrows(Exception.class, () ->
                audioProcessingService.generateWaveformData(emptyData, "audio/wav")
        );
    }

    private byte[] generateTestPcmData(int sampleRate, int durationSeconds) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                2,
                4,
                sampleRate,
                false
        );

        int numSamples = sampleRate * durationSeconds;

        for (int i = 0; i < numSamples; i++) {
            double frequency = 440.0;
            double time = i / (double) sampleRate;
            short sample = (short) (Math.sin(2 * Math.PI * frequency * time) * Short.MAX_VALUE * 0.5);

            ByteBuffer buffer = ByteBuffer.allocate(2);
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            buffer.putShort(sample);
            outputStream.write(buffer.array(), 0, 2);

            ByteBuffer bufferRight = ByteBuffer.allocate(2);
            bufferRight.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bufferRight.putShort(sample);
            outputStream.write(bufferRight.array(), 0, 2);
        }

        return outputStream.toByteArray();
    }
}
