package com.springaimcpservercommon.persistence.audit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link DataKeyProvider}: wraps data keys with an in-memory AES-256 KEK (AES-GCM key wrap, subject key id as
 * associated data). Never use outside tests — a real provider delegates to the host KMS.
 */
public final class InMemoryDataKeyProvider implements DataKeyProvider {

    /** KEK reference reported for wrapped keys. */
    public static final String KEK_REF = "test-kek-1";

    private final SecureRandom random = new SecureRandom();
    private final byte[] kek = new byte[32];
    private final AtomicInteger unwrapCalls = new AtomicInteger();

    /** Creates a provider with a random KEK. */
    public InMemoryDataKeyProvider() {
        random.nextBytes(kek);
    }

    @Override
    public GeneratedDataKey generateDataKey(String subjectKeyId) {
        byte[] dataKey = new byte[32];
        random.nextBytes(dataKey);
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] sealed = crypt(Cipher.ENCRYPT_MODE, nonce, dataKey, subjectKeyId);
        byte[] wrapped = new byte[nonce.length + sealed.length];
        System.arraycopy(nonce, 0, wrapped, 0, nonce.length);
        System.arraycopy(sealed, 0, wrapped, nonce.length, sealed.length);
        return new GeneratedDataKey(KEK_REF, dataKey, wrapped);
    }

    @Override
    public byte[] unwrap(String kekRef, byte[] wrappedKey, String subjectKeyId) {
        if (!KEK_REF.equals(kekRef)) {
            throw new IllegalArgumentException("unknown KEK " + kekRef);
        }
        unwrapCalls.incrementAndGet();
        byte[] nonce = Arrays.copyOfRange(wrappedKey, 0, 12);
        byte[] sealed = Arrays.copyOfRange(wrappedKey, 12, wrappedKey.length);
        return crypt(Cipher.DECRYPT_MODE, nonce, sealed, subjectKeyId);
    }

    /**
     * Number of unwrap calls so far.
     *
     * @return count
     */
    public int unwrapCalls() {
        return unwrapCalls.get();
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] input, String subjectKeyId) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(subjectKeyId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("test key wrap failed", e);
        }
    }
}
