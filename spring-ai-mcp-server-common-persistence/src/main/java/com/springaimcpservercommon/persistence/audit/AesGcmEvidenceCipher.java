package com.springaimcpservercommon.persistence.audit;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * AES-256-GCM evidence cipher: a fresh random 96-bit nonce per encryption, 128-bit authentication tag, the subject's
 * data key unwrapped through the {@link DataKeyProvider} for each operation and wiped right after use.
 *
 * <p>Random 96-bit nonces are safe far beyond realistic evidence volumes per data key (the NIST SP 800-38D bound of
 * 2^32 encryptions per key).
 */
public final class AesGcmEvidenceCipher implements EvidenceCipher {

    /** Nonce length in bytes (96 bits). */
    public static final int NONCE_BYTES = 12;

    /** Authentication tag length in bits. */
    public static final int TAG_BITS = 128;

    /** Data key length in bytes (AES-256). */
    public static final int KEY_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final DataKeyProvider keyProvider;
    private final SecureRandom random;

    /**
     * Creates the cipher with a default {@link SecureRandom}.
     *
     * @param keyProvider host KMS adapter
     */
    public AesGcmEvidenceCipher(DataKeyProvider keyProvider) {
        this(keyProvider, new SecureRandom());
    }

    /**
     * Creates the cipher.
     *
     * @param keyProvider host KMS adapter
     * @param random      nonce source
     */
    public AesGcmEvidenceCipher(DataKeyProvider keyProvider, SecureRandom random) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public SealedEvidence seal(WrappedDataKey key, byte[] plaintext, byte[] associatedData) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] dataKey = unwrap(key);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            return new SealedEvidence(nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public byte[] open(WrappedDataKey key, SealedEvidence sealed, byte[] associatedData) {
        if (sealed.nonce().length != NONCE_BYTES) {
            throw new EvidenceIntegrityException("evidence nonce must be " + NONCE_BYTES + " bytes",
                    new IllegalArgumentException("nonce length " + sealed.nonce().length));
        }
        byte[] dataKey = unwrap(key);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(sealed.ciphertext());
        } catch (AEADBadTagException e) {
            throw new EvidenceIntegrityException("evidence failed authentication", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    private byte[] unwrap(WrappedDataKey key) {
        byte[] dataKey = keyProvider.unwrap(key.kekRef(), key.wrappedKey(), key.subjectKeyId());
        if (dataKey.length != KEY_BYTES) {
            Arrays.fill(dataKey, (byte) 0);
            throw new IllegalStateException("data key must be " + KEY_BYTES + " bytes (AES-256)");
        }
        return dataKey;
    }
}
