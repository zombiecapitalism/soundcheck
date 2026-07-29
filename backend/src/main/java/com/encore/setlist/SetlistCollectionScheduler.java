package com.encore.setlist;

import com.encore.batch.BatchLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일 1회면 충분하다(docs/setlist-schema.md 4장). 해외 공연 등록이 밤사이 쌓이는 것을
 * 감안해 새벽에 돈다. 즉시 재수집은 관리자 콘솔의 수동 트리거로 한다.
 */
@Component
public class SetlistCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SetlistCollectionScheduler.class);

    private final SetlistCollector collector;
    private final BatchLock batchLock;

    public SetlistCollectionScheduler(SetlistCollector collector, BatchLock batchLock) {
        this.collector = collector;
        this.batchLock = batchLock;
    }

    @Scheduled(cron = "${encore.collect.cron:0 30 5 * * *}", zone = "Asia/Seoul")
    public void collectDaily() {
        if (!batchLock.tryAcquireCollect()) {
            log.info("수집이 이미 실행 중이라 이번 스케줄 실행을 건너뛴다");
            return;
        }
        try {
            collector.collectAll();
        } finally {
            batchLock.releaseCollect();
        }
    }
}
