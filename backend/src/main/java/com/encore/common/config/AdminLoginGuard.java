package com.encore.common.config;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 관리자 로그인 브루트포스 방어 — IP당 10분 창 안에서 실패 10회를 넘으면 창이 끝날 때까지
 * 차단(429)한다. Chat에는 레이트리미터를 두면서 로그인에는 없던 비대칭을 닫는다.
 * 단일 인스턴스 전제의 인메모리 고정 창(BatchLock·ChatRateLimiter와 같은 전제).
 */
@Component
public class AdminLoginGuard {

    static final int MAX_FAILURES = 10;
    static final long WINDOW_MILLIS = 10 * 60_000L;

    private record Window(long startedAt, AtomicInteger failures) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean isBlocked(String clientIp, long nowMillis) {
        Window window = windows.get(clientIp);
        return window != null
                && nowMillis - window.startedAt() < WINDOW_MILLIS
                && window.failures().get() >= MAX_FAILURES;
    }

    public void recordFailure(String clientIp, long nowMillis) {
        Window window = windows.compute(clientIp, (key, existing) ->
                existing == null || nowMillis - existing.startedAt() >= WINDOW_MILLIS
                        ? new Window(nowMillis, new AtomicInteger())
                        : existing);
        window.failures().incrementAndGet();
        cleanUp(nowMillis);
    }

    /** 인증 성공 — 정상 운영자의 과거 오타가 누적돼 잠기는 일이 없게 지운다. */
    public void reset(String clientIp) {
        windows.remove(clientIp);
    }

    /** 만료 창 정리 — 방치하면 IP 수만큼 무한히 자란다. */
    private void cleanUp(long nowMillis) {
        if (windows.size() < 1000) {
            return;
        }
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMillis - it.next().getValue().startedAt() >= WINDOW_MILLIS) {
                it.remove();
            }
        }
    }
}
