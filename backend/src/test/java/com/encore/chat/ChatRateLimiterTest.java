package com.encore.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 비용 가드 — 분당 상한과 창 경계, 클라이언트 격리를 검증한다. */
class ChatRateLimiterTest {

    @Test
    void allowsUpToLimitPerMinuteThenRejects() {
        ChatRateLimiter limiter = new ChatRateLimiter();
        long now = 1_000_000_000L;

        for (int i = 0; i < ChatRateLimiter.MAX_PER_MINUTE; i++) {
            assertThat(limiter.tryAcquire("ip:1", now)).isTrue();
        }
        assertThat(limiter.tryAcquire("ip:1", now)).isFalse();
    }

    @Test
    void resetsOnNextMinute() {
        ChatRateLimiter limiter = new ChatRateLimiter();
        long now = 1_000_000_000L;
        for (int i = 0; i <= ChatRateLimiter.MAX_PER_MINUTE; i++) {
            limiter.tryAcquire("ip:1", now);
        }

        assertThat(limiter.tryAcquire("ip:1", now + 60_000)).isTrue();
    }

    /** 다른 IP·이벤트는 서로의 상한을 소모하지 않는다. */
    @Test
    void isolatesClients() {
        ChatRateLimiter limiter = new ChatRateLimiter();
        long now = 1_000_000_000L;
        for (int i = 0; i <= ChatRateLimiter.MAX_PER_MINUTE; i++) {
            limiter.tryAcquire("ip-a:1", now);
        }

        assertThat(limiter.tryAcquire("ip-b:1", now)).isTrue();
        assertThat(limiter.tryAcquire("ip-a:2", now)).isTrue();
    }
}
