package com.encore.chat;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chat(E8) 비용 가드 — IP·이벤트당 분당 요청 제한. Chat은 캐시가 불가능한 자유 질의라
 * 유일한 변동 비용원이다(P3에서 계측과 같은 단계에 묶인 이유).
 * 단일 인스턴스 전제의 인메모리 고정 창 — 규모가 커지면 Redis 등으로 교체한다.
 */
@Component
public class ChatRateLimiter {

    static final int MAX_PER_MINUTE = 5;

    private record Window(long minute, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** true = 허용. 같은 분(minute) 안에서 MAX_PER_MINUTE 초과면 거부. */
    public boolean tryAcquire(String clientKey, long nowMillis) {
        long minute = nowMillis / 60_000;
        Window window = windows.compute(clientKey, (key, existing) ->
                existing == null || existing.minute() != minute
                        ? new Window(minute, new AtomicInteger())
                        : existing);
        boolean allowed = window.count().incrementAndGet() <= MAX_PER_MINUTE;
        cleanUp(minute);
        return allowed;
    }

    /** 지난 분의 창은 더 안 쓴다 — 방치하면 IP 수만큼 무한히 자란다. */
    private void cleanUp(long currentMinute) {
        if (windows.size() < 1000) {
            return;
        }
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().minute() < currentMinute) {
                it.remove();
            }
        }
    }
}
