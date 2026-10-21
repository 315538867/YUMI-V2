package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.file.AttachFileReference;
import com.yumi.platform.api.file.CreateUploadCommand;
import com.yumi.platform.api.file.FileApplicationService;
import com.yumi.platform.api.file.FileContent;
import com.yumi.platform.api.file.FilePolicy;
import com.yumi.platform.api.file.FileQuery;
import com.yumi.platform.api.file.FileReferenceParticipant;
import com.yumi.platform.api.file.FileStatus;
import com.yumi.platform.api.file.UploadRef;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.support.AbstractMySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 文件生命周期（PL-03）。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§12 与
 * `specs/platform-security/spec.md` PL-03。使用真实 MySQL 验证：上传校验、临时文件不可引用、
 * 业务事务失败回滚正式引用、已引用文件不可删除、授权读取与引用方权限。
 *
 * <p>授权在本测试中用显式替身，只覆盖文件生命周期与权限调用点；真实认证与 HTTP 权限绑定在
 * 2.11—2.12 验证。
 */
class FileLifecycleIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Path storageRoot;

    private RecordingAuthorization authorization;

    private FileApplicationService fileService;

    private FileQuery fileQuery;

    private FileReferenceParticipant fileReferences;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws Exception {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbc.sql("DELETE FROM platform_file_references").update();
        jdbc.sql("DELETE FROM platform_files").update();
        storageRoot = Files.createTempDirectory("yumi-files-");
        authorization = new RecordingAuthorization();
        var service = new com.yumi.platform.file.application.FileApplicationService(
                new com.yumi.platform.file.infrastructure.JdbcFileRepository(jdbc),
                new com.yumi.platform.file.infrastructure.FileSystemContentStore(storageRoot),
                FilePolicy.defaults(),
                authorization,
                () -> new com.yumi.platform.api.identity.CurrentActor(
                        com.yumi.platform.api.value.StableBusinessId.of("ACC-1"), "tester",
                        com.yumi.platform.api.value.StableBusinessId.of("SES-1"),
                        Set.of(PermissionCodes.PLATFORM_FILE_UPLOAD, PermissionCodes.CASH_RECEIVE)),
                java.time.Clock.systemUTC());
        fileService = service;
        fileQuery = service;
        fileReferences = service;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storageRoot != null && Files.exists(storageRoot)) {
            try (var paths = Files.walk(storageRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // 清理失败不影响断言
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("上传成功后为临时文件，未被业务引用前不能授权读取")
    void uploadCreatesTemporaryFile() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("receipt.png", "image/png", pngBytes()));

        assertThat(fileQuery.getFile(upload.fileBusinessId().value()).status())
                .isEqualTo(FileStatus.TEMPORARY);
        assertThat(authorization.requiredPermissions).contains(PermissionCodes.PLATFORM_FILE_UPLOAD);

        assertThatThrownBy(() -> fileQuery.readContent(
                upload.fileBusinessId().value(), PermissionCodes.CASH_RECEIVE))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));
    }

    @Test
    @DisplayName("拒绝空文件、超限文件与非允许类型")
    void uploadValidationRejectsInvalidFiles() {
        assertThatThrownBy(() -> fileService.createUpload(
                new CreateUploadCommand("empty.png", "image/png", new byte[0])))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("空文件");

        byte[] tooLarge = new byte[(int) FilePolicy.DEFAULT_MAX_SIZE_BYTES + 1];
        assertThatThrownBy(() -> fileService.createUpload(
                new CreateUploadCommand("big.png", "image/png", tooLarge)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("上限");

        assertThatThrownBy(() -> fileService.createUpload(
                new CreateUploadCommand("evil.exe", "application/x-msdownload", pngBytes())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不支持");

        assertThatThrownBy(() -> fileService.createUpload(
                new CreateUploadCommand("../escape.png", "image/png", pngBytes())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("路径");
    }

    @Test
    @DisplayName("业务引用后文件正式化，引用方可授权读取内容")
    void attachFormalizesAndAllowsAuthorizedRead() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("voucher.pdf", "application/pdf", pngBytes()));
        String fileBusinessId = upload.fileBusinessId().value();

        transactionTemplate.execute(status -> fileReferences.attach(new AttachFileReference(
                fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER")));

        assertThat(fileQuery.getFile(fileBusinessId).status()).isEqualTo(FileStatus.FORMAL);
        assertThat(fileQuery.getFile(fileBusinessId).references()).hasSize(1);

        FileContent content = fileQuery.readContent(fileBusinessId, PermissionCodes.CASH_RECEIVE);
        assertThat(content.content()).isEqualTo(pngBytes());
        assertThat(content.originalName()).isEqualTo("voucher.pdf");
    }

    @Test
    @DisplayName("引用方没有资源权限时拒绝读取，知道文件标识也不能绕过")
    void readRequiresOwnerResourcePermission() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("voucher.png", "image/png", pngBytes()));
        String fileBusinessId = upload.fileBusinessId().value();
        transactionTemplate.execute(status -> fileReferences.attach(new AttachFileReference(
                fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER")));

        authorization.deny(PermissionCodes.PAYROLL_SENSITIVE_READ);

        assertThatThrownBy(() -> fileQuery.readContent(
                fileBusinessId, PermissionCodes.PAYROLL_SENSITIVE_READ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));
    }

    @Test
    @DisplayName("业务事务失败时正式化与引用一并回滚，不留下孤立引用")
    void failedBusinessTransactionLeavesNoReference() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("voucher.png", "image/png", pngBytes()));
        String fileBusinessId = upload.fileBusinessId().value();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            fileReferences.attach(new AttachFileReference(
                    fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER"));
            throw new IllegalStateException("模拟现金保存失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(fileQuery.getFile(fileBusinessId).status()).isEqualTo(FileStatus.TEMPORARY);
        assertThat(fileQuery.getFile(fileBusinessId).references()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_file_references").query(Long.class).single())
                .isZero();
    }

    @Test
    @DisplayName("重复引用同一文件同一用途只保留一条引用记录")
    void duplicateAttachIsIdempotent() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("voucher.png", "image/png", pngBytes()));
        String fileBusinessId = upload.fileBusinessId().value();

        transactionTemplate.execute(status -> {
            fileReferences.attach(new AttachFileReference(
                    fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER"));
            fileReferences.attach(new AttachFileReference(
                    fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER"));
            return null;
        });

        assertThat(fileQuery.getFile(fileBusinessId).references()).hasSize(1);
    }

    @Test
    @DisplayName("仍有业务引用的文件不能删除；解除引用后才允许删除")
    void referencedFileCannotBeDeletedUntilReleased() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("voucher.png", "image/png", pngBytes()));
        String fileBusinessId = upload.fileBusinessId().value();
        transactionTemplate.execute(status -> fileReferences.attach(new AttachFileReference(
                fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER")));

        assertThatThrownBy(() -> fileService.delete(fileBusinessId))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("FILE_IN_USE"));

        transactionTemplate.execute(status -> {
            fileReferences.release(new com.yumi.platform.api.file.ReleaseFileReference(
                    fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER"));
            return null;
        });

        // 引用解除后允许删除：保护的是“仍被业务引用”，而不是正式化状态本身
        fileService.delete(fileBusinessId);
        assertThat(fileQuery.getFile(fileBusinessId).status()).isEqualTo(FileStatus.DELETED);
    }

    @Test
    @DisplayName("未引用的临时文件可以删除，内容一并清理")
    void temporaryFileCanBeDeleted() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("temp.png", "image/png", pngBytes()));
        String fileBusinessId = upload.fileBusinessId().value();

        fileService.delete(fileBusinessId);

        assertThat(fileQuery.getFile(fileBusinessId).status()).isEqualTo(FileStatus.DELETED);
        assertThatThrownBy(() -> fileQuery.readContent(fileBusinessId, PermissionCodes.CASH_RECEIVE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("不同用途引用分别保存，互不覆盖")
    void referencesAreSeparatedByPurpose() {
        UploadRef upload = fileService.createUpload(
                new CreateUploadCommand("shared.png", "image/png", pngBytes()));
        String fileBusinessId = upload.fileBusinessId().value();

        transactionTemplate.execute(status -> {
            fileReferences.attach(new AttachFileReference(
                    fileBusinessId, "finance", "CASH_FACT", "CASH-1", "CASH_VOUCHER"));
            fileReferences.attach(new AttachFileReference(
                    fileBusinessId, "product", "PRODUCT", "PROD-1", "PRODUCT_IMAGE"));
            return null;
        });

        List<String> purposes = fileQuery.getFile(fileBusinessId).references().stream()
                .map(com.yumi.platform.api.file.FileReferenceView::purposeCode)
                .sorted()
                .toList();
        assertThat(purposes).containsExactly("CASH_VOUCHER", "PRODUCT_IMAGE");
    }

    private static byte[] pngBytes() {
        return "fake-png-content".getBytes(StandardCharsets.UTF_8);
    }

    /** 记录调用点的权限替身；真实认证与 HTTP 绑定在 2.11—2.12 验证。 */
    static final class RecordingAuthorization implements AuthorizationService {

        private final List<String> requiredPermissions = new java.util.ArrayList<>();

        private final Set<String> denied = new java.util.HashSet<>();

        void deny(String permissionCode) {
            denied.add(permissionCode);
        }

        @Override
        public void require(String permissionCode, ResourceScope scope) {
            requiredPermissions.add(permissionCode);
            if (denied.contains(permissionCode)) {
                throw new ApiException(
                        ApiErrorType.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有执行该操作的权限：" + permissionCode);
            }
        }

        @Override
        public boolean isPermitted(String permissionCode, ResourceScope scope) {
            return !denied.contains(permissionCode);
        }
    }
}
