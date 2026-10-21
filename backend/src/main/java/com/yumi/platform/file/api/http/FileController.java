package com.yumi.platform.file.api.http;

import com.yumi.platform.api.file.CreateUploadCommand;
import com.yumi.platform.api.file.FileContent;
import com.yumi.platform.api.file.FileView;
import com.yumi.platform.file.application.FileApplicationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件 HTTP 适配器（交付清单 P03）。
 *
 * <p>上传只创建临时文件；正式化与引用随业务事务进行，不提供独立“凭证池”。
 * 读取内容必须声明引用方资源权限点，由 {@code FileQuery} 再次校验，不能仅凭文件标识下载。
 */
@RestController
@RequestMapping("/api/platform/files")
public class FileController {

    private final FileApplicationService files;

    private final com.yumi.platform.idempotency.application.PlatformCommandService commands;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final com.yumi.platform.http.HttpRequestContextResolver contextResolver;

    public FileController(
            FileApplicationService files,
            com.yumi.platform.idempotency.application.PlatformCommandService commands,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            com.yumi.platform.http.HttpRequestContextResolver contextResolver) {
        this.files = files;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.contextResolver = contextResolver;
    }

    /** 上传请求体：内容使用 Base64，避免 JSON 数组膨胀。 */
    public record UploadRequest(String originalName, String mediaType, String contentBase64) {
    }

    /** 上传响应。 */
    public record UploadResponse(
            String fileBusinessId, String originalName, String mediaType, long sizeBytes) {
    }

    @PostMapping
    public UploadResponse upload(
            @RequestBody UploadRequest request, jakarta.servlet.http.HttpServletRequest servletRequest) {
        // 上传是正式写入：必须携带幂等键，重试不重复占用内容存储
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new com.yumi.platform.api.idempotency.CommandRequest(
                        "platform.file.upload",
                        context.idempotencyKey(),
                        com.yumi.platform.idempotency.application.PlatformCommandService.fingerprint(
                                objectMapper, request)),
                () -> {
                    var upload = files.createUpload(new CreateUploadCommand(
                            request.originalName(),
                            request.mediaType(),
                            java.util.Base64.getDecoder().decode(request.contentBase64())));
                    return new com.yumi.platform.api.idempotency.CommandOutcome(
                            "FILE", upload.fileBusinessId().value());
                });
        var upload = files.getFile(execution.outcome().resultBusinessId());
        return new UploadResponse(
                upload.fileBusinessId(), upload.originalName(), upload.mediaType(), upload.sizeBytes());
    }

    @GetMapping("/{fileBusinessId}")
    public FileResponse getFile(@PathVariable String fileBusinessId) {
        FileView view = files.getFile(fileBusinessId);
        List<ReferenceResponse> references = view.references().stream()
                .map(reference -> new ReferenceResponse(
                        reference.referenceBusinessId(), reference.ownerModule(), reference.ownerType(),
                        reference.ownerBusinessId(), reference.purposeCode()))
                .toList();
        return new FileResponse(
                view.fileBusinessId(), view.originalName(), view.mediaType(), view.sizeBytes(),
                view.sha256(), view.status().name(), view.rowVersion(), references, view.createdAt());
    }

    /**
     * 授权读取文件内容。
     *
     * @param permissionCode 引用方资源读取权限点，例如 {@code finance.read}
     */
    @GetMapping("/{fileBusinessId}/content")
    public ResponseEntity<byte[]> readContent(
            @PathVariable String fileBusinessId,
            @RequestParam(name = "permissionCode") String permissionCode) {
        FileContent content = files.readContent(fileBusinessId, permissionCode);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + content.originalName() + "\"")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content.content());
    }

    @DeleteMapping("/{fileBusinessId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String fileBusinessId) {
        files.delete(fileBusinessId);
        return ResponseEntity.ok(Map.of("status", "DELETED", "fileBusinessId", fileBusinessId));
    }

    /** 文件元数据响应。 */
    public record FileResponse(
            String fileBusinessId,
            String originalName,
            String mediaType,
            long sizeBytes,
            String sha256,
            String status,
            long rowVersion,
            List<ReferenceResponse> references,
            java.time.Instant createdAt) {
    }

    /** 文件引用响应。 */
    public record ReferenceResponse(
            String referenceBusinessId, String ownerModule, String ownerType,
            String ownerBusinessId, String purposeCode) {
    }
}
