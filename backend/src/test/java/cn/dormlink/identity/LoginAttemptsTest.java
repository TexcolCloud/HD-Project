package cn.dormlink.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dormlink.shared.api.ApiException;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class LoginAttemptsTest {
    @Test
    void concurrentInFlightAttemptsCannotAllPassBeforeFailuresAreRecorded() {
        var attempts = new LoginAttempts();
        Instant now = Instant.parse("2026-11-12T01:00:00Z");
        for (int i = 0; i < 5; i++) attempts.begin("account-source-digest", now);
        assertThatThrownBy(() -> attempts.begin("account-source-digest", now))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("登录尝试过多");
        attempts.begin("account-source-digest", now.plusSeconds(301));
    }
}
