// ABOUTME: The one place tests compute a Meta webhook signature, plus the body mutation set of spec 0007 AC-1.
// ABOUTME: Issue 145's checked in fixtures should be generated here so the tests never disagree on the HMAC rule.
package io.github.stevdrey.dokene.integration.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSigning {
    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WebhookSigning() {
    }

    /** {@code sha256=} plus the lowercase hex HMAC SHA-256 of {@code body}, keyed with the UTF-8 bytes of the secret. */
    public static String sign(byte[] body, char[] secret) {
        return "sha256=" + hex(hmac(body, secret));
    }

    /** The same signature with uppercase hex digits, which Meta's documentation does not rule out. */
    public static String signUppercase(byte[] body, char[] secret) {
        return "sha256=" + hex(hmac(body, secret)).toUpperCase();
    }

    /** A plausible WhatsApp callback whose only free text is {@code canary}, so leakage checks have one needle. */
    public static byte[] body(String canary) {
        return ("{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"" + canary + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every mutation of {@link #body(String)} that must refuse once the original was signed: one byte changed,
     * appended, removed, whitespace added, keys reordered with different spacing, and the whole body transcoded.
     */
    public static Map<String, byte[]> mutationsOf(String canary) {
        byte[] original = body(canary);
        Map<String, byte[]> mutations = new LinkedHashMap<>();
        byte[] changed = original.clone();
        changed[changed.length / 2] ^= 0x01;
        mutations.put("one byte changed", changed);
        mutations.put("one byte appended", Arrays.copyOf(original, original.length + 1));
        mutations.put("one byte removed", Arrays.copyOf(original, original.length - 1));
        mutations.put("whitespace inside the JSON", ("{ \"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\""
                + canary + "\"}]}").getBytes(StandardCharsets.UTF_8));
        mutations.put("reserialised with different key order and spacing", ("{\"entry\": [{\"id\": \"" + canary
                + "\"}], \"object\": \"whatsapp_business_account\"}").getBytes(StandardCharsets.UTF_8));
        mutations.put("transcoded to UTF-16", new String(original, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_16));
        return mutations;
    }

    private static byte[] hmac(byte[] body, char[] secret) {
        byte[] key = new String(secret).getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException unexpected) {
            throw new IllegalStateException("Test signing failed", unexpected);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            out[index * 2] = HEX[(bytes[index] >> 4) & 0x0F];
            out[index * 2 + 1] = HEX[bytes[index] & 0x0F];
        }
        return new String(out);
    }
}
