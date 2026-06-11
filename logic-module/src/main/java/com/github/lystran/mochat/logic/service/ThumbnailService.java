package com.github.lystran.mochat.logic.service;

import jakarta.inject.Singleton;
import net.coobird.thumbnailator.Thumbnails;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Singleton
public class ThumbnailService {

    private static final int THUMBNAIL_WIDTH = 200;
    private static final int THUMBNAIL_HEIGHT = 200;
    private static final double QUALITY = 0.8;

    public byte[] generateThumbnail(byte[] originalImage, String mimeType) throws IOException {
        if (!isSupportedImageType(mimeType)) {
            throw new IllegalArgumentException("Unsupported image type for thumbnail generation: " + mimeType);
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Thumbnails.of(new ByteArrayInputStream(originalImage))
                .size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
                .keepAspectRatio(true)
                .outputQuality(QUALITY)
                .toOutputStream(outputStream);

        return outputStream.toByteArray();
    }

    private boolean isSupportedImageType(String mimeType) {
        return mimeType != null && (
                mimeType.startsWith("image/jpeg") ||
                        mimeType.startsWith("image/png") ||
                        mimeType.startsWith("image/gif") ||
                        mimeType.startsWith("image/webp")
        );
    }
}
