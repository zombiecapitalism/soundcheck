package com.encore.api;

import org.springframework.http.codec.ServerSentEvent;

/** SSE 프레이밍 공용 헬퍼 — 곡 설명·Chat 컨트롤러가 같은 이벤트 계약을 쓴다. */
final class Sse {

    private Sse() {
    }

    static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
