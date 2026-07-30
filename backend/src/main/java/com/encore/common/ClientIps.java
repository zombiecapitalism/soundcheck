package com.encore.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 레이트리밋·로그인 가드 키용 클라이언트 IP. X-Forwarded-For의 **마지막** 항목을 쓴다 —
 * 첫 항목은 클라이언트가 임의 헤더로 위조할 수 있고(append 방식 프록시), 마지막 항목은
 * 우리 앞의 신뢰 프록시(nginx) 1홉이 직접 본 주소다.
 */
public final class ClientIps {

    private ClientIps() {
    }

    public static String from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].strip();
        }
        return request.getRemoteAddr();
    }
}
