package com.java2nb.novel.core.utils;

import tools.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.bean.UserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import java.util.Base64;
import java.io.ByteArrayOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author 11797
 */
@Component
@Slf4j
public class JwtTokenUtil {

    private static final String CLAIM_KEY_USERNAME = "sub";
    private static final String CLAIM_KEY_CREATED = "created";
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * 根据负责生成JWT的token
     */
    private String generateToken(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .expiration(generateExpirationDate())
                .signWith(signingKey(), Jwts.SIG.HS512)
                .compact();
    }

    /**
     * 从token中获取JWT中的负载
     */
    private Claims getClaimsFromToken(String token) {
        Claims claims = null;
        try {
            var signed = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token);
            if (!"HS512".equals(signed.getHeader().getAlgorithm())) { return null; }
            claims = signed.getPayload();
        } catch (Exception e) {
            claims = legacyClaims(token);
            if (claims == null) { log.debug("JWT格式或签名验证失败"); }
        }
        // Even a successfully signed token must carry a live expiry.
        return claims != null && claims.getExpiration() != null
                && claims.getExpiration().after(new Date()) ? claims : null;
    }

    private SecretKey signingKey() {
        byte[] oldKey = legacyKey();
        if (oldKey.length >= 64) { return new SecretKeySpec(oldKey, "HmacSHA512"); }
        try {
            // JJWT 0.13 requires a 512-bit key. Preserve configuration while deriving a full-sized key.
            byte[] key = MessageDigest.getInstance("SHA-512").digest(
                    ("novel-plus:jwt:hs512:v2:" + secret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "HmacSHA512");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private byte[] legacyKey() {
        // Match JAXB's original base64 decoder: ignore non-alphabet characters and incomplete quartets.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] quartet = new int[4]; int count = 0;
        for (char value : secret.toCharArray()) {
            int decoded = value == '=' ? 127 : alphabet.indexOf(value);
            if (decoded < 0) { continue; }
            quartet[count++] = decoded;
            if (count == 4) {
                out.write((quartet[0] << 2) | (quartet[1] >> 4));
                if (quartet[2] != 127) { out.write((quartet[1] << 4) | (quartet[2] >> 2)); }
                if (quartet[3] != 127) { out.write((quartet[2] << 6) | quartet[3]); }
                count = 0;
            }
        }
        return out.toByteArray();
    }

    private Claims legacyClaims(String token) {
        try {
            if (token == null || token.length() > 16384) { return null; }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) { return null; }
            JsonMapper mapper = JsonMapper.builder().build();
            JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!header.path("alg").asString().equals("HS512") || header.has("crit")) { return null; }
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(legacyKey(), "HmacSHA512"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) { return null; }
            Map<String, Object> values = mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            Claims claims = Jwts.claims().add(values).build();
            if (claims.getExpiration() == null || !claims.getExpiration().after(new Date())) { return null; }
            if (claims.getNotBefore() != null && claims.getNotBefore().after(new Date())) { return null; }
            return claims;
        } catch (Exception e) { return null; }
    }

    /**
     * 生成token的过期时间
     */
    private Date generateExpirationDate() {
        return new Date(System.currentTimeMillis() + expiration * 1000);
    }

    /**
     * 从token中获取用户信息
     */
    public UserDetails getUserDetailsFromToken(String token) {
        return userDetails(getClaimsFromToken(token));
    }

    private UserDetails userDetails(Claims claims) {
        if (claims == null) { return null; }
        try {
            UserDetails details = new ObjectMapper().readValue(claims.getSubject(), UserDetails.class);
            return details != null && details.getId() != null && details.getUsername() != null
                    && !details.getUsername().isBlank() ? details : null;
        } catch (Exception e) {
            log.debug("JWT用户信息无效");
            return null;
        }
    }

    /**
     * 根据用户信息生成token
     */
    @SneakyThrows
    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>(2);
        claims.put(CLAIM_KEY_USERNAME, new ObjectMapper().writeValueAsString(userDetails));
        claims.put(CLAIM_KEY_CREATED, new Date());
        return generateToken(claims);
    }

    /**
     * 判断token是否可以被刷新
     */
    public boolean canRefresh(String token) {
        return getUserDetailsFromToken(token) != null;
    }

    /**
     * 刷新token
     */
    public String refreshToken(String token) {
        RefreshedToken refreshed = refreshTokenWithDetails(token);
        return refreshed == null ? null : refreshed.token();
    }

    /** Validate once so a second parse cannot expire or lose the verified identity. */
    public RefreshedToken refreshTokenWithDetails(String token) {
        Claims claims = getClaimsFromToken(token);
        UserDetails details = userDetails(claims);
        if (details == null) { return null; }
        Date now = new Date();
        if (claims.getExpiration() == null || !claims.getExpiration().after(now)) { return null; }
        // JJWT 0.13 Claims, including the legacy compatibility result, are immutable.
        Map<String, Object> values = new HashMap<>(claims);
        values.put(CLAIM_KEY_CREATED, now);
        return new RefreshedToken(generateToken(values), details);
    }

    public record RefreshedToken(String token, UserDetails userDetails) {}


}
