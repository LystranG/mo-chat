package com.github.lystran.mochat.logic.service;

public class MediaUploadException extends RuntimeException {

    public MediaUploadException(String message) {
        super(message);
    }

    public MediaUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
