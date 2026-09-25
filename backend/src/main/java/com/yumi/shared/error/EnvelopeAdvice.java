package com.yumi.shared.error;

import com.yumi.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把控制器成功返回值统一包进 ApiEnvelope；错误路径由异常处理与过滤器直接产出信封，不经此装饰。
 */
@ControllerAdvice
public class EnvelopeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, org.springframework.http.MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || body instanceof ApiEnvelope) {
            return body;
        }
        // 导出例外：文本/文件类响应（如 `text/csv` 台账导出）不套信封——导出内容是文件而非业务负载，
        // 与 `204` 同属统一信封的例外；JSON 业务响应一律照旧包装。
        if (contentType != null && ("text".equalsIgnoreCase(contentType.getType())
                || org.springframework.http.MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(contentType))) {
            return body;
        }
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        return ApiEnvelope.ok(body, RequestIdFilter.currentId(servletRequest));
    }
}
