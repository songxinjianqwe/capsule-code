package com.xinjian.capsulecode.controller;

import com.xinjian.capsulecode.shared.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class FileController {
    private final FileStorage fileStorage;

    /**
     * 上传文件。同时接受：
     * - POST /files/upload（对外新接口）
     * - POST /chat/upload（兼容 Android ApiService.uploadFile，字段与主 app ChatController 对齐）
     * 返回 {fileId, fileUuid, fileName, mimeType}。fileId 与 fileUuid 同值，只是为了兼容不同客户端。
     */
    @PostMapping({"/files/upload", "/chat/upload"})
    public Map<String, String> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "deviceId", required = false) String deviceId
    ) throws IOException {
        String uuid = fileStorage.upload(file, deviceId);
        Map<String, String> resp = new HashMap<>();
        resp.put("fileId", uuid);
        resp.put("fileUuid", uuid);
        resp.put("fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "");
        resp.put("mimeType", file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        log.info("[FileController] upload name={} mime={} size={} uuid={} deviceId={}",
                resp.get("fileName"), resp.get("mimeType"), file.getSize(), uuid, deviceId);
        return resp;
    }

    /** 下载。同时接受 /files/{uuid} 和 /chat/file/{uuid}（主 app 旧路径） */
    @GetMapping({"/files/{uuid}", "/chat/file/{uuid}"})
    public ResponseEntity<InputStreamResource> download(@PathVariable String uuid) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(fileStorage.download(uuid)));
    }

    @GetMapping("/files/{uuid}/path")
    public Map<String, String> path(@PathVariable String uuid) {
        return Map.of("path", fileStorage.resolvePath(uuid));
    }
}
