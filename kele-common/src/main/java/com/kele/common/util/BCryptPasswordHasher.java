package com.kele.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * v0.13 #1: 密码哈希工具（BCrypt）。
 * <p>
 * 替代旧的 {@link AESUtil} 对称加密方案：
 * <ul>
 *   <li>AES（旧）：可逆，DB+源码泄漏 = 全员密码可还原。工业级漏洞。</li>
 *   <li>BCrypt（新）：单向哈希 + 内置 salt + 可调 cost factor（默认 10），DB 泄漏无法直接还原明文。</li>
 * </ul>
 *
 * <p><b>双轨迁移</b>：兼容期内（旧 AES 密文）调用方（{@code SysUserInfoAOImpl.verifyAndMigrate}）
 * 会先 {@link #isBCryptHash(String)} 判断，BCrypt 走 {@link #matches} 比对，AES 走解密比对；
 * 命中 AES 时调用方用 {@link #hash(String)} 重新哈希并更新 DB（透明迁移）。
 */
public final class BCryptPasswordHasher {

    /** cost factor = 10（约 60ms/hash，对登录性能可接受，对暴力破解有效阻尼） */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    private BCryptPasswordHasher() {}

    /** 哈希明文密码（用于 register / changePassword）。 */
    public static String hash(String plainPassword) {
        return ENCODER.encode(plainPassword);
    }

    /** BCrypt 比对（用于已迁移的密码）。 */
    public static boolean matches(String plainPassword, String bcryptHash) {
        return ENCODER.matches(plainPassword, bcryptHash);
    }

    /** 判断 stored 是否为 BCrypt hash（"$2a$" / "$2b$" / "$2y$" 开头）。 */
    public static boolean isBCryptHash(String stored) {
        return stored != null && stored.length() >= 4
            && (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"));
    }
}
