package br.com.codelikeaboss.skillsommelier.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. The algorithm is mandatory on every JVM, so its absence is treated as a bug. */
final class Sha256 {

    private Sha256() {}

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hex(String value) {
        MessageDigest digest = newDigest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return hex(digest);
    }
}
