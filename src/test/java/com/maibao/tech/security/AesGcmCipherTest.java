package com.maibao.tech.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

    @Test
    void should_round_trip_and_mask() throws Exception {
        AesGcmCipher cipher = new AesGcmCipher("unit-test-secret");
        String encrypted = cipher.encrypt("sk-1234567890abcdef");
        assertThat(encrypted).isNotEqualTo("sk-1234567890abcdef");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-1234567890abcdef");
        assertThat(AesGcmCipher.mask("sk-1234567890abcdef")).isEqualTo("sk-1****cdef");
    }
}
