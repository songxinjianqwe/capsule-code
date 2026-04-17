package com.xinjian.capsulecode.model;

import lombok.Data;

@Data
public class ChatFile {
    private Long id;
    private String fileUuid;
    private String fileName;
    private String mimeType;
    private String filePath;
    private String deviceId;
}
