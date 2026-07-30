package com.encore.common.config;

import com.encore.common.ClientIps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * /api/admin/** 로그인 시도 제한 필터 — 차단된 IP는 인증 시도 전에 429로 끊고,
 * 401 응답(자격증명 실패)을 실패로 집계하며, 성공하면 카운터를 지운다.
 * 인증 필터 앞에 서야 차단 중에는 비밀번호 검증 자체가 실행되지 않는다.
 */
@Component
public class AdminLoginGuardFilter extends OncePerRequestFilter {

    private final AdminLoginGuard guard;

    public AdminLoginGuardFilter(AdminLoginGuard guard) {
        this.guard = guard;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = ClientIps.from(request);
        long now = System.currentTimeMillis();
        if (guard.isBlocked(clientIp, now)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"status\":429,\"title\":\"시도 제한\",\"detail\":\"로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.\"}");
            return;
        }
        filterChain.doFilter(request, response);
        if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
            guard.recordFailure(clientIp, now);
        } else if (response.getStatus() < 400) {
            guard.reset(clientIp);
        }
    }
}
