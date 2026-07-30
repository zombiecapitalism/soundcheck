package com.encore.batch;

import com.encore.llm.LlmCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 운영 로그 retention — collection_log·llm_call_log는 무한히 자라는 유이한 테이블이다.
 * 90일이면 튜닝 로그(분기 단위 회고)에 충분하고, 그 이전 기록은 대시보드가 조회하지 않는다.
 */
@Component
public class LogRetention {

    private static final Logger log = LoggerFactory.getLogger(LogRetention.class);
    static final int RETENTION_DAYS = 90;

    private final CollectionLogRepository collectionLogRepository;
    private final LlmCallLogRepository llmCallLogRepository;

    public LogRetention(CollectionLogRepository collectionLogRepository,
                        LlmCallLogRepository llmCallLogRepository) {
        this.collectionLogRepository = collectionLogRepository;
        this.llmCallLogRepository = llmCallLogRepository;
    }

    /** 일일 파이프라인(05:30)보다 앞서 새벽에 정리 — 겹쳐도 서로 다른 행이라 무해하다. */
    @Scheduled(cron = "${encore.retention.cron:0 10 5 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(RETENTION_DAYS));
        long batchLogs = collectionLogRepository.deleteByStartedAtBefore(cutoff);
        long llmLogs = llmCallLogRepository.deleteByCreatedAtBefore(cutoff);
        if (batchLogs > 0 || llmLogs > 0) {
            log.info("로그 retention({}일): collection_log {}건, llm_call_log {}건 삭제",
                    RETENTION_DAYS, batchLogs, llmLogs);
        }
    }
}
