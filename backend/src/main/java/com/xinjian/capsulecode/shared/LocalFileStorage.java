package com.xinjian.capsulecode.shared;

import com.xinjian.capsulecode.mapper.ChatFileMapper;
import com.xinjian.capsulecode.model.ChatFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 本地磁盘 + capsule_code.chat_file 表。capsule-code 后端唯一的文件存储实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileStorage implements FileStorage {

    @Value("${capsule-code.file-storage.local.root-dir:/tmp/capsule-code/uploads}")
    private String rootDir;

    private final ChatFileMapper mapper;

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(Paths.get(rootDir));
        log.info("[FileStorage] ready rootDir={}", rootDir);
    }

    @Override
    public String upload(MultipartFile file, String deviceId) throws IOException {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String orig = file.getOriginalFilename();
        String ext = (orig != null && orig.contains(".")) ? orig.substring(orig.lastIndexOf('.')) : "";
        Path dst = Paths.get(rootDir, uuid + ext);
        file.transferTo(dst);

        // HEIC/HEIF 检测 + 转 JPEG（Anthropic API 只支持 PNG/JPEG/GIF/WEBP；iPhone/新安卓默认拍 HEIC）
        String finalMime = file.getContentType();
        Path finalPath = dst;
        if (isHeic(dst)) {
            Path jpg = Paths.get(rootDir, uuid + ".jpg");
            if (convertHeicToJpeg(dst, jpg)) {
                Files.deleteIfExists(dst);
                finalPath = jpg;
                finalMime = "image/jpeg";
                log.info("[FileStorage] HEIC → JPEG converted: {} → {}", dst.getFileName(), jpg.getFileName());
            } else {
                log.warn("[FileStorage] HEIC 转换失败，保留原文件（Claude 可能无法识别）: {}", dst);
            }
        }

        ChatFile cf = new ChatFile();
        cf.setFileUuid(uuid);
        cf.setFileName(orig);
        cf.setMimeType(finalMime);
        cf.setFilePath(finalPath.toString());
        // chat_file.device_id NOT NULL；客户端没传用 "unknown" 占位
        cf.setDeviceId(deviceId != null && !deviceId.isBlank() ? deviceId : "unknown");
        mapper.insertFile(cf);

        log.info("[FileStorage] uploaded name={} size={} uuid={} path={} mime={}",
                orig, file.getSize(), uuid, finalPath, finalMime);
        return uuid;
    }

    /** 通过文件头判断是否 HEIC/HEIF（客户端上传 MIME 可能被错标成 image/png） */
    private boolean isHeic(Path path) {
        try (var in = Files.newInputStream(path)) {
            byte[] head = in.readNBytes(12);
            // ISO BMFF 容器：bytes 4..7 == "ftyp"，bytes 8..11 判断 brand
            if (head.length < 12) return false;
            if (head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p') return false;
            String brand = new String(head, 8, 4);
            // HEIC: heic/heix/heim/heis/hevc; HEIF: mif1/msf1
            return brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1") || brand.equals("msf1");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * HEIC → JPEG，按平台依次尝试：
     *   1. macOS: sips -s format jpeg input --out output.jpg
     *   2. Linux: heif-convert input.heic output.jpg（libheif-examples 提供）
     *   3. 通用: magick convert input.heic output.jpg（ImageMagick 7+）
     */
    private boolean convertHeicToJpeg(Path src, Path dst) {
        String[][] candidates = {
                {"sips", "-s", "format", "jpeg", src.toString(), "--out", dst.toString()},
                {"heif-convert", src.toString(), dst.toString()},
                {"magick", "convert", src.toString(), dst.toString()},
        };
        for (String[] cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) { p.destroyForcibly(); continue; }
                if (p.exitValue() == 0 && Files.exists(dst) && Files.size(dst) > 0) {
                    log.debug("[FileStorage] HEIC converted via {}", cmd[0]);
                    return true;
                }
            } catch (Exception e) {
                // 下一个 candidate
            }
        }
        log.warn("[FileStorage] 所有 HEIC→JPEG 工具都不可用（sips/heif-convert/magick 都失败）");
        return false;
    }

    @Override
    public InputStream download(String fileUuid) throws IOException {
        ChatFile cf = mapper.selectById(fileUuid);
        if (cf == null) throw new FileNotFoundException("fileUuid not found: " + fileUuid);
        return Files.newInputStream(Paths.get(cf.getFilePath()));
    }

    @Override
    public String resolvePath(String fileUuid) {
        ChatFile cf = mapper.selectById(fileUuid);
        if (cf == null) throw new IllegalArgumentException("fileUuid not found: " + fileUuid);
        return cf.getFilePath();
    }
}
