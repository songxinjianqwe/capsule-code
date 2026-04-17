package com.xinjian.capsulecode.shared;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件上传/下载抽象。当前唯一实现 {@link LocalFileStorage}。
 * Claude 发消息时通过 resolvePath 拿磁盘路径拼进 content block。
 */
public interface FileStorage {

    /** 上传文件，返回 fileUuid。deviceId 会写入 chat_file.device_id，可为 null（落 "unknown"）。 */
    String upload(MultipartFile file, String deviceId) throws IOException;

    /** 根据 fileUuid 返回文件流（调用方负责关闭）。 */
    InputStream download(String fileUuid) throws IOException;

    /** 返回文件在磁盘上的绝对路径；Claude 子进程跑在主后端机器时也要能 resolve。 */
    String resolvePath(String fileUuid);
}
