package com.encore.api;

import com.encore.prediction.AccuracyCalculator.AccuracyReport;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/events/{id}/accuracy — 공연 후 예측 vs 실제 비교(F5 확장, PRD 2차 "적중률 공개").
 * precisionAtK가 헤드라인이다: "상위 K곡(=실제 곡 수)을 예습했다면 몇 곡을 맞췄나".
 */
public record AccuracyResponse(
        int actualSongCount,
        int topK,
        int topKHits,
        BigDecimal precisionAtK,
        int totalHits,
        BigDecimal recall,
        List<SongResult> results,
        List<Surprise> surprises
) {

    public record SongResult(int rank, String songKey, String songName, BigDecimal probability,
                             boolean played, Integer actualPosition) {
    }

    public record Surprise(String songName, int actualPosition) {
    }

    public static AccuracyResponse from(AccuracyReport report) {
        return new AccuracyResponse(
                report.actualSongCount(),
                report.topK(),
                report.topKHits(),
                report.precisionAtK(),
                report.totalHits(),
                report.recall(),
                report.results().stream()
                        .map(r -> new SongResult(r.rank(), r.songKey(), r.songName(), r.probability(),
                                r.played(), r.actualPosition()))
                        .toList(),
                report.surprises().stream()
                        .map(s -> new Surprise(s.songName(), s.actualPosition()))
                        .toList());
    }
}
