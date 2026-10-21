package com.yumi.platform.identity.application.port;

/** 口令哈希端口：只保存强哈希，禁止明文或可逆加密。 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
