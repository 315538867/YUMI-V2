package com.yumi.platform.api.file;

/**
 * 文件命令：上传、正式化与删除保护。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9：临时文件不能引用；正式化与业务提交
 * 同事务；已引用文件不能直接删除。
 */
public interface FileApplicationService {

    UploadRef createUpload(CreateUploadCommand command);

    /** 把临时文件正式化；需与调用方业务写入处于同一事务。 */
    FormalFileRef formalize(String fileBusinessId);

    /** 删除文件；仍有有效引用时拒绝。 */
    void delete(String fileBusinessId);
}
