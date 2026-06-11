package com.github.lystran.mochat.logic.service;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("mochat.media.storage")
public record MediaStorageConfig(
    StorageType type,
    RustfsConfig rustfs,
    long maxFileSize,
    String[] allowedTypes
) {
    public enum StorageType {
        rustfs,
        s3,
        filesystem
    }
}
