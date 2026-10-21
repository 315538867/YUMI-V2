package com.yumi.platform.identity.application.port;

/** 会话令牌哈希端口：服务端只保存哈希。 */
public interface TokenHasher {

    String hash(String rawToken);
}
