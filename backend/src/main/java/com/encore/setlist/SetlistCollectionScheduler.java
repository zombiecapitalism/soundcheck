package com.encore.setlist;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일 1회면 충분하다(docs/setlist-schema.md 4장). 해외 공연 등록이 밤사이 쌓이는 것을
 * 감안해 새벽에 돈다. 페스티벌 직후에는 수동 트리거로 즉시 재수집할 예정.
 */
@Component
public class SetlistCollectionScheduler {

    private final SetlistCollector collector;

    public SetlistCollectionScheduler(SetlistCollector collector) {
        this.collector = collector;
    }

    @Scheduled(cron = "${encore.collect.cron:0 30 5 * * *}", zone = "Asia/Seoul")
    public void collectDaily() {
        collector.collectAll();
    }
}
