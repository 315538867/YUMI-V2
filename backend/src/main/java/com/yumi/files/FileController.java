package com.yumi.files;

import com.yumi.files.api.FileResponse;
import com.yumi.files.api.FileStorageService;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 文件上传（multipart 参数名 file，需登录 + Idempotency-Key）与按 id 下载字节流。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileStorageService storageService;

    public FileController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping
    public FileResponse upload(@RequestParam(value = "file", required = false) MultipartFile file,
                               HttpServletRequest request) throws IOException {
        if (file == null) {
            var message = "缺少文件参数 file";
            throw new ApiException(ErrorCode.VALIDATION_INVALID, message,
                    List.of(new ApiFieldError("file", message)));
        }
        return storageService.store(file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                RequestIdFilter.currentId(request), request.getHeader("Idempotency-Key"));
    }

    @GetMapping("/{id}")
    public void download(@PathVariable long id, HttpServletResponse response) throws IOException {
        var metadata = storageService.getMetadata(id);
        var bytes = storageService.read(metadata);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(metadata.contentType());
        response.setHeader("Content-Disposition", "inline");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }
}
