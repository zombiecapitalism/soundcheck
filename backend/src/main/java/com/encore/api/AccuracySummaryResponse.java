package com.encore.api;

import com.encore.prediction.AccuracyCalculator.AccuracyReport;
import com.encore.prediction.TargetEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** GET /api/events/accuracy 항목 — 지난 공연들의 예측 성적 아카이브(신뢰의 누적 증거). */
public record AccuracySummaryResponse(
        Long eventId,
        String eventName,
        LocalDate eventDate,
        UUID artistMbid,
        String artistName,
        int actualSongCount,
        int topK,
        int topKHits,
        BigDecimal precisionAtK,
        BigDecimal f1,
        int top5Hits,
        int top5Size,
        int top10Hits,
        int top10Size
) {

    /** event는 artist·actualSetlist가 fetch join으로 로드된 상태를 전제한다. */
    public static AccuracySummaryResponse from(TargetEvent event, AccuracyReport report) {
        return new AccuracySummaryResponse(
                event.getId(),
                event.getEventName(),
                event.getEventDate(),
                event.getArtist().getMbid(),
                event.getArtist().getName(),
                report.actualSongCount(),
                report.topK(),
                report.topKHits(),
                report.precisionAtK(),
                report.f1(),
                report.top5().hits(),
                report.top5().size(),
                report.top10().hits(),
                report.top10().size());
    }
}
