package com.fixcampus.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fixcampus.attachments")
public record AttachmentProperties(Path storageDir, int maxFileBytes, int maxWidth, int maxHeight, long maxPixels,
                                   int maxStudentPerOrder, int maxWorkerPerOrder, long maxTotalBytes) {
    public AttachmentProperties {
        if (maxFileBytes <= 0 || maxWidth <= 0 || maxHeight <= 0 || maxPixels <= 0
                || maxStudentPerOrder <= 0 || maxWorkerPerOrder <= 0 || maxTotalBytes <= 0) {
            throw new IllegalArgumentException("Attachment limits must be positive.");
        }
    }
}
