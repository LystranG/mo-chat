package com.github.lystran.mochat.multimedia.service;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

/**
 * 音频处理服务（基于 FFmpeg）
 * 
 * 提供音频转码和波形生成功能：
 * - 将各种音频格式转码为 MP3（统一格式，便于播放）
 * - 生成音频波形数据（用于客户端可视化预览）
 */
@Singleton
public class AudioProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AudioProcessingService.class);

    private static final int TARGET_SAMPLE_RATE = 44100;
    private static final int TARGET_BIT_RATE = 128000;
    private static final int WAVEFORM_POINTS = 100;

    /**
     * 将音频转码为 MP3 格式
     * 
     * @param audioData 原始音频数据
     * @param mimeType 原始 MIME 类型
     * @return MP3 格式的音频数据
     */
    public byte[] transcodeAudio(byte[] audioData, String mimeType) throws IOException {
        if (!isSupportedAudioType(mimeType)) {
            throw new IllegalArgumentException("Unsupported audio type for transcoding: " + mimeType);
        }

        log.info("Starting audio transcoding with FFmpeg, mimeType={}, dataSize={} bytes", 
                mimeType, audioData.length);

        File inputFile = null;
        File outputFile = null;

        try {
            // 创建临时输入文件
            String inputExtension = getExtensionFromMimeType(mimeType);
            inputFile = File.createTempFile("audio_input_", "." + inputExtension);
            Files.write(inputFile.toPath(), audioData);

            // 创建临时输出文件
            outputFile = File.createTempFile("audio_output_", ".mp3");

            // 使用 FFmpeg 转码为 MP3
            // 命令：ffmpeg -i input.wav -acodec libmp3lame -b:a 128k -ar 44100 -ac 2 output.mp3
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputFile.getAbsolutePath(),
                "-acodec", "libmp3lame",      // MP3 编码器
                "-b:a", String.valueOf(TARGET_BIT_RATE),  // 比特率 128kbps
                "-ar", String.valueOf(TARGET_SAMPLE_RATE), // 采样率 44.1kHz
                "-ac", "2",                  // 双声道
                outputFile.getAbsolutePath()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                log.error("FFmpeg audio transcoding failed: {}", error);
                throw new RuntimeException("FFmpeg failed to transcode audio to MP3");
            }

            byte[] result = Files.readAllBytes(outputFile.toPath());
            log.info("Audio transcoding completed, originalSize={} bytes, mp3Size={} bytes",
                    audioData.length, result.length);

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg process interrupted", e);
        } finally {
            // 清理临时文件
            if (inputFile != null && inputFile.exists()) {
                inputFile.delete();
            }
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    /**
     * 生成音频波形数据（用于客户端可视化预览）
     * 
     * @param audioData 音频数据（应该是 MP3 格式）
     * @param mimeType MIME 类型
     * @return Base64 编码的波形数据
     */
    public String generateWaveformData(byte[] audioData, String mimeType) throws IOException {
        if (!isSupportedAudioType(mimeType)) {
            throw new IllegalArgumentException("Unsupported audio type for waveform generation: " + mimeType);
        }

        log.info("Generating waveform data with FFmpeg, mimeType={}, dataSize={} bytes", 
                mimeType, audioData.length);

        File inputFile = null;
        File outputFile = null;

        try {
            // 创建临时输入文件
            String inputExtension = getExtensionFromMimeType(mimeType);
            inputFile = File.createTempFile("waveform_input_", "." + inputExtension);
            Files.write(inputFile.toPath(), audioData);

            // 创建临时输出文件（原始 PCM 数据）
            outputFile = File.createTempFile("waveform_output_", ".raw");

            // 使用 FFmpeg 提取原始音频数据并降采样
            // 命令：ffmpeg -i input.mp3 -ac 1 -ar 8000 -f s16le output.raw
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputFile.getAbsolutePath(),
                "-ac", "1",           // 单声道（简化波形计算）
                "-ar", "8000",        // 8kHz 采样率（降低数据量）
                "-f", "s16le",        // 16-bit little-endian PCM
                outputFile.getAbsolutePath()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                log.error("FFmpeg waveform extraction failed: {}", error);
                throw new RuntimeException("FFmpeg failed to extract waveform data");
            }

            // 读取原始 PCM 数据
            byte[] pcmData = Files.readAllBytes(outputFile.toPath());
            
            // 转换为波形采样点
            float[] waveform = downsampleToPoints(pcmData, WAVEFORM_POINTS);
            
            // Base64 编码
            String encodedWaveform = Base64.getEncoder().encodeToString(floatsToBytes(waveform));
            
            log.info("Waveform generation completed, points={}, encodedSize={} bytes",
                    WAVEFORM_POINTS, encodedWaveform.length());

            return encodedWaveform;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("FFmpeg process interrupted", e);
        } finally {
            // 清理临时文件
            if (inputFile != null && inputFile.exists()) {
                inputFile.delete();
            }
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    /**
     * 检查是否支持该音频类型
     */
    private boolean isSupportedAudioType(String mimeType) {
        return mimeType != null && (
                mimeType.startsWith("audio/mpeg") ||
                mimeType.startsWith("audio/wav") ||
                mimeType.startsWith("audio/ogg") ||
                mimeType.startsWith("audio/flac") ||
                mimeType.startsWith("audio/aac") ||
                mimeType.startsWith("audio/mp4") ||
                mimeType.equals("audio/x-m4a") ||
                mimeType.startsWith("audio/webm")
        );
    }

    /**
     * 根据 MIME 类型获取文件扩展名
     */
    private String getExtensionFromMimeType(String mimeType) {
        if (mimeType.contains("mpeg") || mimeType.contains("mp3")) return "mp3";
        if (mimeType.contains("wav")) return "wav";
        if (mimeType.contains("ogg")) return "ogg";
        if (mimeType.contains("flac")) return "flac";
        if (mimeType.contains("aac") || mimeType.contains("m4a")) return "m4a";
        if (mimeType.contains("webm")) return "webm";
        return "bin";
    }

    /**
     * 将 PCM 数据降采样到指定点数
     */
    private float[] downsampleToPoints(byte[] pcmData, int targetPoints) {
        if (pcmData.length == 0) {
            return new float[targetPoints];
        }

        // PCM 是 16-bit，每 2 字节一个采样点
        int sampleCount = pcmData.length / 2;
        int samplesPerPoint = Math.max(1, sampleCount / targetPoints);

        float[] waveform = new float[targetPoints];

        for (int i = 0; i < targetPoints; i++) {
            int startIdx = i * samplesPerPoint * 2;
            int endIdx = Math.min((i + 1) * samplesPerPoint * 2, pcmData.length);

            float sum = 0;
            int count = 0;
            
            for (int j = startIdx; j < endIdx - 1; j += 2) {
                // 16-bit little-endian
                short sample = (short) ((pcmData[j + 1] << 8) | (pcmData[j] & 0xFF));
                sum += Math.abs(sample / 32768.0f);
                count++;
            }

            waveform[i] = count > 0 ? sum / count : 0;
        }

        return waveform;
    }

    /**
     * 将 float 数组转换为 byte 数组
     */
    private byte[] floatsToBytes(float[] floats) {
        byte[] bytes = new byte[floats.length * 2];
        for (int i = 0; i < floats.length; i++) {
            short s = (short) (floats[i] * Short.MAX_VALUE);
            bytes[i * 2] = (byte) (s & 0xFF);
            bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return bytes;
    }
}