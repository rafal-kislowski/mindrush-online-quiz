package pl.mindrush.backend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class SecureTokenUtils {

    private static final SecureRandom RNG = new SecureRandom();

    private SecureTokenUtils() {
    }

    public static String randomUrlSafeToken(int numBytes) {
        int safeBytes = Math.max(16, numBytes);
        byte[] bytes = new byte[safeBytes];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 error", ex);
        }
    }
}

