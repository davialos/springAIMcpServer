package com.springaimcpservercommon.persistence.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEvidenceCipherTest {

    private final InMemoryDataKeyProvider provider = new InMemoryDataKeyProvider();
    private final AesGcmEvidenceCipher cipher = new AesGcmEvidenceCipher(provider);
    private final byte[] plaintext = "prompt: show orders of ACME".getBytes(StandardCharsets.UTF_8);
    private final byte[] aad = "dai-evidence-v1|row-1".getBytes(StandardCharsets.UTF_8);

    private WrappedDataKey keyFor(String subject) {
        DataKeyProvider.GeneratedDataKey generated = provider.generateDataKey(subject);
        return new WrappedDataKey(subject, generated.kekRef(), generated.wrappedKey());
    }

    @Test
    void roundTripsWithA96BitNonce() {
        WrappedDataKey key = keyFor("principal:1");

        SealedEvidence sealed = cipher.seal(key, plaintext, aad);

        assertThat(sealed.nonce()).hasSize(12);
        assertThat(sealed.ciphertext()).hasSize(plaintext.length + 16);
        assertThat(new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("ACME");
        assertThat(cipher.open(key, sealed, aad)).isEqualTo(plaintext);
        assertThat(provider.unwrapCalls()).isEqualTo(2);
    }

    @Test
    void usesAFreshNonceEveryTime() {
        WrappedDataKey key = keyFor("principal:1");

        SealedEvidence a = cipher.seal(key, plaintext, aad);
        SealedEvidence b = cipher.seal(key, plaintext, aad);

        assertThat(a.nonce()).isNotEqualTo(b.nonce());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    @Test
    void detectsTamperingWrongContextAndWrongKey() {
        WrappedDataKey key = keyFor("principal:1");
        SealedEvidence sealed = cipher.seal(key, plaintext, aad);

        byte[] tampered = sealed.ciphertext().clone();
        tampered[0] ^= 1;
        assertThatThrownBy(() -> cipher.open(key, new SealedEvidence(sealed.nonce(), tampered), aad))
                .isInstanceOf(EvidenceIntegrityException.class);
        assertThatThrownBy(() -> cipher.open(key, sealed, "other-row".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EvidenceIntegrityException.class);
        assertThatThrownBy(() -> cipher.open(keyFor("principal:2"), sealed, aad))
                .isInstanceOf(EvidenceIntegrityException.class);
    }

    @Test
    void rejectsKeysThatAreNotAes256() {
        DataKeyProvider shortKeys = new DataKeyProvider() {
            @Override
            public GeneratedDataKey generateDataKey(String subjectKeyId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] unwrap(String kekRef, byte[] wrappedKey, String subjectKeyId) {
                return new byte[16];
            }
        };
        AesGcmEvidenceCipher weak = new AesGcmEvidenceCipher(shortKeys);

        assertThatThrownBy(() -> weak.seal(new WrappedDataKey("s", "k", new byte[1]), plaintext, aad))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AES-256");
    }
}
