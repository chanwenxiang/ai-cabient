package com.aicabinet.common.dto;

public record FileAttachmentDto(
        Long fileId,
        String fileName,
        String contentType,
        Long fileSize,
        String url
) {
    public static FileAttachmentDto of(Long fileId, String fileName, String contentType, Long fileSize, String url) {
        return new FileAttachmentDto(fileId, fileName, contentType, fileSize, url);
    }
}
