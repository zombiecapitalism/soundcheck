package com.encore.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일 1회면 충분하다(docs/setlist-schema.md 4장). 해외 공연 등록이 밤사이 쌓이는 것을
 * 감안해 새벽에 돌고, 수집이 끝나면 적중률 매칭·예측 재계산까지 이어진다.
 * 즉시 재수집은 관리자 콘솔의 수동 트리거로 한다(같은 파이프라인).
 */
@Component
public class DailyCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyCollectionScheduler.class);

    private final CollectionPipeline pipeline;

    public DailyCollectionScheduler(CollectionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(cron = "${encore.collect.cron:0 30 5 * * *}", zone = "Asia/Seoul")
    public void collectDaily() {
        if (!pipeline.tryStartCollection()) {
            log.info("수집이 이미 실행 중이라 이번 스케줄 실행을 건너뛴다");
        }
    }
}
