package com.yumi.platform.api.file;

/**
 * 文件查询与授权读取。
 *
 * <p>下载/预览必须校验<b>引用方资源权限</b>，不能仅凭文件标识读取；无引用或引用方无权限时拒绝，
 * 避免知道文件 ID 就绕过业务权限。
 */
public interface FileQuery {

    FileView getFile(String fileBusinessId);

    /**
     * 授权读取文件内容。
     *
     * @param requestingPermissionCode 引用方资源读取权限点，由调用方模块给出
     */
    FileContent readContent(String fileBusinessId, String requestingPermissionCode);
}
