package com.springaimcpservercommon.core.hash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {

    @Test
    void rendersKnownDigest() {
        assertThat(Sha256.of("abc"))
                .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void validatesFormat() {
        assertThat(Sha256.isValid(Sha256.of("x"))).isTrue();
        assertThat(Sha256.isValid("sha256:XYZ")).isFalse();
        assertThat(Sha256.isValid("md5:00")).isFalse();
    }
}
