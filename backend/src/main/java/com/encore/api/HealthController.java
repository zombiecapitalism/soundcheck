package com.encore.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 업타임 모니터(cron-job.org 등)용 최소 헬스 체크.
 * 응답 크기 상한이 있는 모니터가 있어 본문 없이 204만 반환한다.
 * DB 등 의존성 검사는 하지 않는다 — 슬립 방지 핑이 목적이라 프로세스 생존만 확인하면 된다.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Void> health() {
        return ResponseEntity.noContent().build();
    }
}
