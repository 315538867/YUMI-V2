package com.yumi.platform.file.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.file.AttachFileReference;
import com.yumi.platform.api.file.CreateUploadCommand;
import com.yumi.platform.api.file.FileContent;
import com.yumi.platform.api.file.FilePolicy;
import com.yumi.platform.api.file.FileQuery;
import com.yumi.platform.api.file.FileReferenceParticipant;
import com.yumi.platform.api.file.FileReferenceView;
import com.yumi.platform.api.file.FileStatus;
import com.yumi.platform.api.file.FileView;
import com.yumi.platform.api.file.FormalFileRef;
import com.yumi.platform.api.file.ReleaseFileReference;
import com.yumi.platform.api.file.UploadRef;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.file.application.port.FileContentStore;
import com.yumi.platform.file.application.port.FileRecord;
import com.yumi.platform.file.application.port.FileReferenceRecord;
import com.yumi.platform.file.application.port.FileRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件应用服务：上传校验、正式化、引用与删除保护。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§12：
 * <ul>
 *   <li>上传时校验大小与媒体类型，超限或不支持的类型直接拒绝；</li>
 *   <li>临时文件不能被业务引用；引用时在同一事务内正式化；</li>
 *   <li>业务事务失败时，正式化与引用一并回滚，不留下孤立引用；</li>
 *   <li>仍有有效引用的文件不能删除；</li>
 *   <li>下载/预览按引用方资源权限校验，仅凭文件标识不能读取。</li>
 * </ul>
 *
 * <p>本服务不提供跨事务的“先正式化后引用”：{@link #formalize} 与
 * {@link #attach} 都要求调用方已有事务。
 */
@Transactional
public class FileApplicationService implements FileServiceContract {

    private final FileRepository files;

    private final FileContentStore contentStore;

    private final FilePolicy policy;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final Clock clock;

    public FileApplicationService(
            FileRepository files,
            FileContentStore contentStore,
            FilePolicy policy,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock) {
        this.files = Objects.requireNonNull(files, "文件仓储不能为空");
        this.contentStore = Objects.requireNonNull(contentStore, "内容存储不能为空");
        this.policy = Objects.requireNonNull(policy, "文件策略不能为空");
        this.authorization = Objects.requireNonNull(authorization, "权限服务不能为空");
        this.currentActor = Objects.requireNonNull(currentActor, "当前操作者不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    @Override
    public UploadRef createUpload(CreateUploadCommand command) {
        Objects.requireNonNull(command, "上传命令不能为空");
        authorization.require(PermissionCodes.PLATFORM_FILE_UPLOAD, ResourceScope.GLOBAL);

        byte[] content = command.content();
        if (content == null || content.length == 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "EMPTY_FILE", "不能上传空文件");
        }
        if (content.length > policy.maxSizeBytes()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION,
                    "FILE_TOO_LARGE",
                    "文件大小超过上限 " + (policy.maxSizeBytes() / 1024) + " KB");
        }
        String mediaType = normalizeMediaType(command.mediaType());
        if (!policy.allowedMediaTypes().contains(mediaType)) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "UNSUPPORTED_FILE_TYPE", "不支持的文件类型：" + mediaType);
        }
        String originalName = normalizeName(command.originalName());

        String sha256 = sha256(content);
        String storageKey = contentStore.store(sha256, content);

        Instant now = clock.instant();
        String accountBusinessId = currentActor.requireCurrentActor().accountBusinessId().value();
        String fileBusinessId = "FILE-" + UUID.randomUUID();

        files.insertFile(new FileRecord(
                0L, fileBusinessId, storageKey, originalName, mediaType, content.length, sha256,
                FileStatus.TEMPORARY, 0L, now, accountBusinessId, now, accountBusinessId));

        return new UploadRef(
                StableBusinessId.of(fileBusinessId), originalName, mediaType, content.length);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FormalFileRef formalize(String fileBusinessId) {
        FileRecord file = requireFile(fileBusinessId);
        if (file.status() == FileStatus.FORMAL) {
            return new FormalFileRef(
                    StableBusinessId.of(file.fileBusinessId()), file.mediaType(), file.sha256());
        }
        if (file.status() != FileStatus.TEMPORARY) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "FILE_NOT_TEMPORARY", "文件当前状态不可正式化：" + file.status());
        }
        authorizedUpdateStatus(file, FileStatus.FORMAL);
        return new FormalFileRef(StableBusinessId.of(file.fileBusinessId()), file.mediaType(), file.sha256());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FormalFileRef attach(AttachFileReference command) {
        Objects.requireNonNull(command, "引用命令不能为空");
        requireReferenceIdentity(command.fileBusinessId(), command.ownerModule(), command.ownerType(),
                command.ownerBusinessId(), command.purposeCode());

        FileRecord file = requireFile(command.fileBusinessId());
        if (file.status() == FileStatus.QUARANTINED || file.status() == FileStatus.DELETED) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "FILE_NOT_FORMAL", "文件已隔离或删除，不能引用");
        }

        // 先正式化，再登记引用；两者与调用方业务写入同事务
        FormalFileRef formal = formalize(command.fileBusinessId());

        if (files.findReference(
                command.fileBusinessId(), command.ownerModule(), command.ownerType(),
                command.ownerBusinessId(), command.purposeCode()).isEmpty()) {
            Instant now = clock.instant();
            files.insertReference(new FileReferenceRecord(
                    0L,
                    "FREF-" + UUID.randomUUID(),
                    command.fileBusinessId(),
                    command.ownerModule(),
                    command.ownerType(),
                    command.ownerBusinessId(),
                    command.purposeCode(),
                    now,
                    currentActor.requireCurrentActor().accountBusinessId().value()));
        }
        return formal;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(ReleaseFileReference command) {
        Objects.requireNonNull(command, "释放命令不能为空");
        requireReferenceIdentity(command.fileBusinessId(), command.ownerModule(), command.ownerType(),
                command.ownerBusinessId(), command.purposeCode());

        files.findReference(
                        command.fileBusinessId(), command.ownerModule(), command.ownerType(),
                        command.ownerBusinessId(), command.purposeCode())
                .ifPresent(reference -> files.deleteReference(reference.referenceBusinessId()));
    }

    @Override
    public void delete(String fileBusinessId) {
        FileRecord file = requireFile(fileBusinessId);
        if (file.status() == FileStatus.DELETED) {
            return;
        }
        if (files.countReferences(fileBusinessId) > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "FILE_IN_USE", "文件仍被业务引用，不能删除");
        }
        if (!files.markDeleted(
                fileBusinessId, file.rowVersion(), clock.instant(),
                currentActor.requireCurrentActor().accountBusinessId().value())) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "ROW_VERSION_CONFLICT", "文件已被其他操作修改，请刷新后重试");
        }
        contentStore.delete(file.storageKey());
    }

    @Override
    public FileView getFile(String fileBusinessId) {
        FileRecord file = requireFile(fileBusinessId);
        List<FileReferenceView> references = files.findReferencesOfFile(fileBusinessId).stream()
                .map(reference -> new FileReferenceView(
                        reference.referenceBusinessId(), reference.ownerModule(), reference.ownerType(),
                        reference.ownerBusinessId(), reference.purposeCode()))
                .toList();
        return new FileView(
                file.fileBusinessId(), file.originalName(), file.mediaType(), file.sizeBytes(),
                file.sha256(), file.status(), file.rowVersion(), references, file.createdAt());
    }

    @Override
    public FileContent readContent(String fileBusinessId, String requestingPermissionCode) {
        FileRecord file = requireFile(fileBusinessId);
        if (file.status() == FileStatus.DELETED || file.status() == FileStatus.QUARANTINED) {
            throw new ApiException(ApiErrorType.NOT_FOUND, "FILE_NOT_AVAILABLE", "文件不可用");
        }
        List<FileReferenceRecord> references = files.findReferencesOfFile(fileBusinessId);
        if (references.isEmpty()) {
            throw new ApiException(ApiErrorType.FORBIDDEN, "FILE_NOT_REFERENCED", "文件未被业务引用，不能读取");
        }
        if (requestingPermissionCode == null || requestingPermissionCode.isBlank()) {
            throw new IllegalArgumentException("读取文件必须声明引用方资源权限点");
        }
        // 引用方资源权限：不能用文件标识绕过业务权限
        authorization.require(requestingPermissionCode, ResourceScope.GLOBAL);

        return new FileContent(file.originalName(), file.mediaType(), contentStore.read(file.storageKey()));
    }

    private void authorizedUpdateStatus(FileRecord file, FileStatus target) {
        if (!files.updateStatus(
                file.fileBusinessId(), target, file.rowVersion(), clock.instant(),
                currentActor.requireCurrentActor().accountBusinessId().value())) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "ROW_VERSION_CONFLICT", "文件已被其他操作修改，请刷新后重试");
        }
    }

    private FileRecord requireFile(String fileBusinessId) {
        if (fileBusinessId == null || fileBusinessId.isBlank()) {
            throw new IllegalArgumentException("文件标识不能为空");
        }
        return files.findFile(fileBusinessId)
                .orElseThrow(() -> new ApiException(ApiErrorType.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在"));
    }

    private static void requireReferenceIdentity(
            String fileBusinessId, String ownerModule, String ownerType, String ownerBusinessId,
            String purposeCode) {
        for (var entry : List.of(
                java.util.Map.entry("文件标识", fileBusinessId),
                java.util.Map.entry("引用方模块", ownerModule),
                java.util.Map.entry("引用方类型", ownerType),
                java.util.Map.entry("引用方业务标识", ownerBusinessId),
                java.util.Map.entry("用途代码", purposeCode))) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException(entry.getKey() + "不能为空");
            }
        }
    }

    private static String normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_MEDIA_TYPE", "缺少文件媒体类型");
        }
        String normalized = mediaType.strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.contains("/")) {
            throw new ApiException(ApiErrorType.VALIDATION, "INVALID_MEDIA_TYPE", "文件媒体类型不合法");
        }
        return normalized;
    }

    private static String normalizeName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_FILE_NAME", "缺少文件名");
        }
        String name = originalName.strip();
        if (name.length() > 255) {
            throw new ApiException(ApiErrorType.VALIDATION, "FILE_NAME_TOO_LONG", "文件名过长");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ApiException(ApiErrorType.VALIDATION, "INVALID_FILE_NAME", "文件名不能包含路径分隔符");
        }
        return name;
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    /** 便捷构造：默认策略与固定只读允许类型。 */
    public static Set<String> defaultAllowedTypes() {
        return FilePolicy.DEFAULT_ALLOWED_MEDIA_TYPES;
    }

    static String utf8(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}
