package com.java2nb.novel.user;

import com.java2nb.novel.core.utils.JwtTokenUtil;
import com.java2nb.novel.core.bean.UserDetails;
import io.jsonwebtoken.Jwts;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class JwtUpgradeCompatibilityTest {
    private static final String SHORT_SECRET = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo=";
    private JwtTokenUtil util() {
        var value = new JwtTokenUtil();
        ReflectionTestUtils.setField(value, "secret", SHORT_SECRET);
        ReflectionTestUtils.setField(value, "expiration", 3600L);
        return value;
    }
    private String legacy(long expiration, String algorithm) throws Exception {
        var encode = Base64.getUrlEncoder().withoutPadding();
        String header = encode.encodeToString(("{\"alg\":\"" + algorithm + "\"}").getBytes(StandardCharsets.UTF_8));
        String payload = encode.encodeToString(("{\"sub\":\"{\\\"id\\\":777,\\\"username\\\":\\\"compat-fixture\\\"}\",\"exp\":" + expiration + "}")
                .getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SHORT_SECRET), "HmacSHA512"));
        return header + "." + payload + "." + encode.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
    }
    @Test void currentTokensWorkWithTheOriginalShortConfiguration() {
        var utility = util(); var details = new UserDetails(); details.setId(777L); details.setUsername("compat-fixture");
        assertEquals(details, utility.getUserDetailsFromToken(utility.generateToken(details)));
    }
    @Test void originalHs512TokensRemainReadableUntilTheirOriginalExpiry() throws Exception {
        var details = util().getUserDetailsFromToken(legacy(System.currentTimeMillis()/1000 + 3600, "HS512"));
        assertNotNull(details); assertEquals(777L, details.getId());
    }
    @Test void expiredAndAlteredLegacySignaturesAreRejected() throws Exception {
        assertNull(util().getUserDetailsFromToken(legacy(System.currentTimeMillis()/1000 - 10, "HS512")));
        String valid = legacy(System.currentTimeMillis()/1000 + 3600, "HS512");
        int start = valid.lastIndexOf('.') + 1;
        String forged = valid.substring(0, start) + (valid.charAt(start) == 'A' ? 'B' : 'A') + valid.substring(start + 1);
        assertNull(util().getUserDetailsFromToken(forged));
    }
    @Test void aDifferentAlgorithmCannotUseTheCompatibilityPath() throws Exception {
        assertNull(util().getUserDetailsFromToken(legacy(System.currentTimeMillis()/1000 + 3600, "none")));
        assertNull(util().getUserDetailsFromToken(legacy(System.currentTimeMillis()/1000 + 3600, "HS256")));
    }

    @Test void realRefreshCopiesCurrentClaimsAndRenewsTheirExpiryAndCreation() throws Exception {
        var utility = util();
        ReflectionTestUtils.setField(utility, "expiration", 30L);
        var details = new UserDetails(); details.setId(777L); details.setUsername("compat-fixture"); details.setNickName("读者");
        String original = utility.generateToken(details);
        var mapper = JsonMapper.builder().build();
        var before = mapper.readTree(Base64.getUrlDecoder().decode(original.split("\\.")[1]));
        ReflectionTestUtils.setField(utility, "expiration", 3600L);
        var refreshed = utility.refreshTokenWithDetails(original);
        assertNotNull(refreshed); assertEquals(details, refreshed.userDetails());
        assertEquals(details, utility.getUserDetailsFromToken(refreshed.token()));
        var after = mapper.readTree(Base64.getUrlDecoder().decode(refreshed.token().split("\\.")[1]));
        assertEquals(before.get("sub"), after.get("sub"));
        assertTrue(after.get("exp").asLong() > before.get("exp").asLong());
        assertTrue(after.get("created").asLong() >= before.get("created").asLong());
        assertEquals(before, mapper.readTree(Base64.getUrlDecoder().decode(original.split("\\.")[1])));
    }

    @Test void realRefreshUpgradesLegacyTokenWithoutChangingTheIdentity() throws Exception {
        var utility = util();
        String original = legacy(System.currentTimeMillis()/1000 + 30, "HS512");
        String refreshed = utility.refreshToken(original);
        assertNotNull(refreshed); assertNotEquals(original, refreshed);
        assertEquals(utility.getUserDetailsFromToken(original), utility.getUserDetailsFromToken(refreshed));
        assertTrue(utility.canRefresh(refreshed));
    }

    @Test void realRefreshRefusesExpiredTamperedEmptyAndWrongAlgorithmTokens() throws Exception {
        var utility = util();
        String valid = legacy(System.currentTimeMillis()/1000 + 3600, "HS512");
        int signature = valid.lastIndexOf('.') + 1;
        String tampered = valid.substring(0, signature) + (valid.charAt(signature) == 'A' ? 'B' : 'A') + valid.substring(signature + 1);
        for (String rejected : new String[]{null, "", "bad", tampered,
                legacy(System.currentTimeMillis()/1000 - 1, "HS512"),
                legacy(System.currentTimeMillis()/1000, "HS512"),
                legacy(System.currentTimeMillis()/1000 + 3600, "HS256")}) {
            assertNull(utility.refreshTokenWithDetails(rejected));
            assertNull(utility.refreshToken(rejected));
            assertFalse(utility.canRefresh(rejected));
        }
    }

    @Test void validSignatureCannotRefreshAMalformedUserOrATokenWithoutExpiry() {
        var utility = util();
        var key = (javax.crypto.SecretKey)ReflectionTestUtils.invokeMethod(utility, "signingKey");
        for (String subject : new String[]{"null", "{}", "not-json", "{\"id\":777}", "{\"id\":777,\"username\":\" \"}"}) {
            String token = Jwts.builder().subject(subject).expiration(new java.util.Date(System.currentTimeMillis()+3600000))
                    .signWith(key, Jwts.SIG.HS512).compact();
            assertNull(utility.refreshTokenWithDetails(token));
        }
        String subject = "{\"id\":777,\"username\":\"compat-fixture\"}";
        String missingExpiry = Jwts.builder().subject(subject).signWith(key, Jwts.SIG.HS512).compact();
        assertNull(utility.refreshToken(missingExpiry));
        assertNull(utility.refreshTokenWithDetails(missingExpiry));
        assertNull(utility.getUserDetailsFromToken(missingExpiry));
        assertFalse(utility.canRefresh(missingExpiry));
        assertNull(utility.refreshToken(Jwts.builder().subject(subject).expiration(new java.util.Date(System.currentTimeMillis()+3600000))
                .signWith(key, Jwts.SIG.HS256).compact()));
    }
}
