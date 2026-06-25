package com.github.lystran.mochat.multimedia.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("mochat.media.storage.rustfs")
public record RustfsConfig(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region
) {
    public String endpoint() {
        return (endpoint != null && !endpoint.isBlank()) ? endpoint : "http://localhost:9000";
    }

    public String accessKey() {
        return (accessKey != null && !accessKey.isBlank()) ? accessKey : null;
    }

    public String secretKey() {
        return (secretKey != null && !secretKey.isBlank()) ? secretKey : null;
    }

    public String bucket() {
        return (bucket != null && !bucket.isBlank()) ? bucket : "picgo";
    }

    public String region() {
        return (region != null && !region.isBlank()) ? region : "auto";
    }
}
