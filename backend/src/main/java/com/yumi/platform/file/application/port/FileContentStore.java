package com.yumi.platform.file.application.port;

/** 文件内容存储端口：只保存/读取字节，不判断业务权限。 */
public interface FileContentStore {

    /** 写入内容并返回存储键；同一内容多次写入得到同一存储键。 */
    String store(String sha256, byte[] content);

    byte[] read(String storageKey);

    void delete(String storageKey);
}
