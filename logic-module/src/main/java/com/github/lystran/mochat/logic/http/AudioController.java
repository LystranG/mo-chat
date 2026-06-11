package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.AudioProcessingService;
import com.github.lystran.mochat.logic.service.MediaStorageConfig;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

@Controller("/audio")
public class AudioController {

    private static final Logger log = LoggerFactory.getLogger(AudioController.class);

    private final AudioProcessingService audioProcessingService;
    private final S3Client s3Client;
    private final String bucketName;

    @Inject
    public AudioController(AudioProcessingService audioProcessingService, MediaStorageConfig config) {
        this.audioProcessingService = Objects.requireNonNull(audioProcessingService, "audioProcessingService");
        Objects.requireNonNull(config, "config");
        
        var rustfsConfig = config.rustfs();
        String endpoint = rustfsConfig.endpoint();
        
        log.info("Initializing AudioController with RustFS endpoint={}", endpoint);
        
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(rustfsConfig.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(rustfsConfig.accessKey(), rustfsConfig.secretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
        
        this.bucketName = rustfsConfig.bucket();
    }

    @Get("/waveform/{objectName+}")
    public Map<String, Object> getWaveform(@PathVariable String objectName) {
        log.info("Generating waveform for audio file, objectName={}", objectName);
        
        try {
            byte[] audioData = downloadAudioData(objectName);
            String mimeType = inferMimeType(objectName);
            
            String waveformData = audioProcessingService.generateWaveformData(audioData, mimeType);
            
            log.info("Waveform generated successfully, objectName={}, waveformDataSize={} bytes", 
                    objectName, waveformData.length());
            
            return Map.of(
                "success", true,
                "waveform", waveformData,
                "points", 100
            );
        } catch (Exception e) {
            log.error("Failed to generate waveform, objectName={}", objectName, e);
            throw new RuntimeException("Failed to generate waveform", e);
        }
    }

    @Get("/transcode/{objectName+}")
    public HttpResponse<byte[]> transcode(@PathVariable String objectName,
                                         @QueryValue(defaultValue = "mp3") String format) {
        log.info("Transcoding audio file, objectName={}, targetFormat={}", objectName, format);
        
        try {
            byte[] audioData = downloadAudioData(objectName);
            String mimeType = inferMimeType(objectName);
            
            byte[] transcodedData = audioProcessingService.transcodeAudio(audioData, mimeType);
            String outputMimeType = getOutputMimeType(format);
            
            log.info("Audio transcoded successfully, objectName={}, outputSize={} bytes, outputMimeType={}", 
                    objectName, transcodedData.length, outputMimeType);
            
            return HttpResponse.ok(transcodedData)
                .header("Content-Type", outputMimeType)
                .header("Content-Disposition", "inline; filename=\"" + objectName + "\"");
        } catch (Exception e) {
            log.error("Failed to transcode audio, objectName={}, format={}", objectName, format, e);
            throw new RuntimeException("Failed to transcode audio", e);
        }
    }

    private byte[] downloadAudioData(String objectName) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .build()
            ).asByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download audio: " + objectName, e);
        }
    }

    private String inferMimeType(String filename) {
        if (filename == null) return "application/octet-stream";

        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".m4a")) return "audio/x-m4a";

        return "application/octet-stream";
    }

    private String getOutputMimeType(String format) {
        return switch (format.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            default -> "audio/mpeg";
        };
    }
}
