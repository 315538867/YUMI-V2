package com.yumi.platform.api.file;

/** 文件状态（基线 06 §6）。 */
public enum FileStatus {
    /** 已上传但尚未被业务正式引用。 */
    TEMPORARY,
    /** 已被业务提交正式引用。 */
    FORMAL,
    /** 校验失败或被隔离，不可引用。 */
    QUARANTINED,
    /** 已删除；只有无有效引用的文件才允许进入。 */
    DELETED
}
