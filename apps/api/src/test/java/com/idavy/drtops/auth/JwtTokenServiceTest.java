package com.idavy.drtops.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    @Test
    void rejectsSecretsShorterThanThirtyTwoBytes() {
        AuthConfiguration configuration = new AuthConfiguration();
        configuration.setJwtSecret("too-short");

        assertThatThrownBy(() -> new JwtTokenService(configuration))
                .isInstanceOf(IllegalStateException.class);
    }
}
