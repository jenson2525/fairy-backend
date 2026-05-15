package cn.nuaa.jensonxu.fairy.integration.service.tools.utils;

import cn.nuaa.jensonxu.fairy.integration.service.tools.config.QWeatherProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;

/**
 * 和风天气 JWT 令牌提供者
 * 在应用启动时解析 Ed25519 私钥，按需手动构造并签发 JWT，
 * 在有效期内缓存 Token 避免频繁签名。
 * JWT 结构严格遵循和风天气要求：Header 仅含 alg/kid，
 * Payload 含 sub/iat/exp，不含 typ 等多余字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QWeatherJwtProvider {

    private final QWeatherProperties properties;

    private PrivateKey privateKey;
    private volatile String cachedToken;
    private volatile long tokenGeneratedAt;

    /** Token 缓存时长：23 小时，与 exp 24 小时有效期对齐，留 1 小时安全边际 */
    private static final long TOKEN_TTL_SECONDS = 840;

    /**
     * 应用启动时解析 Ed25519 私钥，快速失败保证配置正确性
     */
    @PostConstruct
    public void init() {
        try {
            String pem = properties.getPrivateKey()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            this.privateKey = keyFactory.generatePrivate(keySpec);

            log.info("[qweather-jwt][debug] 私钥原始字符串长度: {}", properties.getPrivateKey().length());
            log.info("[qweather-jwt][debug] 去除头尾后 Base64 长度: {}", pem.length());
            log.info("[qweather-jwt][debug] 解码后字节数: {}（Ed25519 PKCS#8 应为 48 字节）", keyBytes.length);

            log.info("[qweather-jwt] Ed25519 私钥加载成功");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[qweather-jwt] 私钥加载失败，请检查配置: " + e.getMessage(), e);
        }
    }

    /**
     * 获取当前有效的 JWT Token，缓存期内复用，超期后自动重新签发
     *
     * @return 签发好的 JWT 字符串
     */
    public synchronized String getToken() {
        long now = System.currentTimeMillis() / 1000;
        if (cachedToken == null || (now - tokenGeneratedAt) >= TOKEN_TTL_SECONDS) {
            try {
                cachedToken = generateToken();
                tokenGeneratedAt = now;
                log.info("[qweather-jwt] Token 已刷新, 下次刷新时间约 {} 秒后", TOKEN_TTL_SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "[qweather-jwt] Token 生成失败: " + e.getMessage(), e);
            }
        }
        return cachedToken;
    }

    /**
     * 手动构造并签发 JWT
     * Header 仅含 alg(EdDSA) 和 kid，Payload 含 sub、iat(提前30秒)、exp(24小时后)，
     * 使用 Java 21 原生 EdDSA 签名，不引入额外字段。
     */
    private String generateToken() throws Exception {
        long iat = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond() - 30;
        long exp = iat + 900;

        String headerJson  = "{\"alg\": \"EdDSA\", \"kid\": \"" + properties.getKeyId() + "\"}";
        String payloadJson = "{\"sub\": \"" + properties.getProjectId() + "\", \"iat\": " + iat + ", \"exp\": " + exp + "}";

        String headerEncoded  = Base64.getUrlEncoder().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadEncoded = Base64.getUrlEncoder().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String data = headerEncoded + "." + payloadEncoded;

        Signature signer = Signature.getInstance("EdDSA");
        signer.initSign(privateKey);
        signer.update(data.getBytes(StandardCharsets.UTF_8));
        String signatureEncoded = Base64.getUrlEncoder().encodeToString(signer.sign());

        log.info("[qweather-jwt][debug] Header JSON: {}", headerJson);
        log.info("[qweather-jwt][debug] Payload JSON: {}", payloadJson);
        log.info("[qweather-jwt][debug] 生成的 JWT: {}", data + "." + signatureEncoded);

        return data + "." + signatureEncoded;
    }
}