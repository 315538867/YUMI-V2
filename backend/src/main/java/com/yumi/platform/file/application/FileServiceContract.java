package com.yumi.platform.file.application;

import com.yumi.platform.api.file.FileApplicationService;
import com.yumi.platform.api.file.FileQuery;
import com.yumi.platform.api.file.FileReferenceParticipant;

/**
 * 文件服务实现契约：同时实现命令、查询与引用参与能力。
 *
 * <p>业务模块只依赖 {@link FileApplicationService}、{@link FileQuery} 与
 * {@link FileReferenceParticipant} 这三个公开接口。
 */
public interface FileServiceContract
        extends FileApplicationService, FileQuery, FileReferenceParticipant {
}
