package com.yumi.platform.file.infrastructure;

import com.yumi.platform.file.application.port.FileContentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 文件内容存储：基于文件系统的内容寻址存储。
 *
 * <p>存储键由内容 SHA-256 派生，天然去重；路径经过严格校验，拒绝越出根目录的键，避免路径穿越。
 * 内容存储不判断业务权限——授权由 {@code FileQuery.readContent} 在引用方权限校验后进行。
 */
public class FileSystemContentStore implements FileContentStore {

    private static final Pattern STORAGE_KEY = Pattern.compile("[0-9a-f]{2}/[0-9a-f]{64}");

    private final Path root;

    public FileSystemContentStore(Path root) {
        this.root = Objects.requireNonNull(root, "存储根目录不能为空").toAbsolutePath().normalize();
    }

    @Override
    public String store(String sha256, byte[] content) {
        Objects.requireNonNull(sha256, "内容指纹不能为空");
        Objects.requireNonNull(content, "文件内容不能为空");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("内容指纹必须为 64 位小写十六进制");
        }
        String storageKey = sha256.substring(0, 2) + "/" + sha256;
        Path target = resolveSafely(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return storageKey;
        } catch (IOException exception) {
            throw new IllegalStateException("文件内容写入失败", exception);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        Path target = resolveSafely(storageKey);
        try {
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new IllegalStateException("文件内容读取失败", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolveSafely(storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("文件内容删除失败", exception);
        }
    }

    private Path resolveSafely(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("非法存储键：" + storageKey);
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("存储键越出根目录：" + storageKey);
        }
        return resolved;
    }
}
