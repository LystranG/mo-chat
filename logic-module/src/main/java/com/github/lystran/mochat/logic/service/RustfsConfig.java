package com.github.lystran.mochat.logic.service;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("rustfs")
record RustfsConfig(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region
) {
    public String endpoint() {
        return endpoint != null ? endpoint : "http://localhost:9000";
    }

    public String accessKey() {
        return accessKey != null ? accessKey : "rustfsadmin";
    }

    public String secretKey() {
        return secretKey != null ? secretKey : "rustfsadmin";
    }

    public String bucket() {
        return bucket != null ? bucket : "mochat-media";
    }

    public String region() {
        return region != null ? region : "cn-local";
    }
}
