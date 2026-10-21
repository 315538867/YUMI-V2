package com.yumi.platform.api.http;

import jakarta.servlet.http.HttpServletRequest;

/** HTTP 适配层读取请求上下文（请求编号与幂等键）的公开契约。 */
public interface RequestContextResolver {

    RequestContext resolveCommand(HttpServletRequest request);

    RequestContext resolveQuery(HttpServletRequest request);

    String resolveRequestId(HttpServletRequest request);
}
