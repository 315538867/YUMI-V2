package com.yumi.platform.api.file;

/**
 * 业务引用参与接口：加入调用方事务，不开启独立事务。
 *
 * <p>任一步失败时，正式化与引用一并回滚，不留下孤立引用。
 */
public interface FileReferenceParticipant {

    FormalFileRef attach(AttachFileReference command);

    void release(ReleaseFileReference command);
}
