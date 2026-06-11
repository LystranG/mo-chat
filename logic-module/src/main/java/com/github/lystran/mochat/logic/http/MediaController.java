package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.service.MediaStorageService;
import com.github.lystran.mochat.logic.service.MediaStorageService.MediaUploadResult;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Map;

@Controller("/media")
public class MediaController {

    private final MediaStorageService mediaStorageService;

    @Inject
    public MediaController(MediaStorageService mediaStorageService) {
        this.mediaStorageService = mediaStorageService;
    }

    @Post(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA)
    public Map<String, Object> upload(CompletedFileUpload file) throws IOException {
        if (file == null || file.getSize() == 0) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        byte[] data = file.getInputStream().readAllBytes();
        String originalFilename = file.getFilename();
        String mimeType = file.getContentType().map(ct -> ct.toString()).orElse("application/octet-stream");

        MediaUploadResult result = mediaStorageService.upload(data, originalFilename, mimeType);

        return Map.of(
            "success", true,
            "mediaId", result.mediaId(),
            "mediaUrl", result.mediaUrl(),
            "objectName", result.objectName(),
            "fileSize", result.fileSize(),
            "mimeType", result.mimeType(),
            "fileName", result.fileName()
        );
    }

    @Get("/download/{objectName+}")
    public HttpResponse<byte[]> download(@PathVariable String objectName) {
        byte[] data = mediaStorageService.download(objectName);
        String mimeType = inferMimeType(objectName);
        
        return HttpResponse.ok(data)
            .header(HttpHeaders.CONTENT_TYPE, mimeType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + objectName + "\"");
    }

    @Get("/presigned-url/{objectName+}")
    public Map<String, Object> presignedUrl(@PathVariable String objectName,
                                            @QueryValue(defaultValue = "3600") int expirySeconds) {
        String url = mediaStorageService.generatePresignedUrl(objectName, expirySeconds);
        return Map.of("success", true, "url", url, "expiresIn", expirySeconds);
    }

    @Delete("/{objectName+}")
    public Map<String, Object> delete(@PathVariable String objectName) {
        mediaStorageService.delete(objectName);
        return Map.of("success", true, "message", "File deleted successfully", "objectName", objectName);
    }

    private String inferMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
