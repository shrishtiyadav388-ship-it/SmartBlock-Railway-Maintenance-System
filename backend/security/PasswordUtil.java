package com.smartblock.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Base64;
public final class PasswordUtil {
    private PasswordUtil() { }

    public static boolean matches(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        String[] parts = storedHash.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
        try {
            int rounds = Integer.parseInt(parts[1]);
            if (rounds < 100_000 || rounds > 500_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length < 16 || expected.length != 32) return false;
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, expected.length * 8);
            try {
                byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
                return MessageDigest.isEqual(expected, actual);
            } finally {
                spec.clearPassword();
            }
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            return false;
        }
    }
}
