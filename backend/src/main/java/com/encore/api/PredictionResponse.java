package com.encore.api;

import com.encore.prediction.EvidenceJson;
import com.encore.prediction.Prediction;
import com.encore.prediction.PredictionCalculator.Evidence;
import com.encore.prediction.PredictionCalculator.Trend;

import java.math.BigDecimal;

/**
 * GET /api/events/{id}/predictions 항목 — 배치가 사전 계산한 값을 그대로 옮긴다(F5: 근거 수치 노출).
 * "최근 sampleSize회 중 playedCount회 연주"가 화면에 그대로 나가는 근거다.
 * recentCount5·trend는 evidence(E4)에서 꺼낸다 — v0.2 이전 스냅샷이면 null.
 */
public record PredictionResponse(
        int rank,
        String songKey,
        String songName,
        BigDecimal probability,
        int playedCount,
        int sampleSize,
        BigDecimal avgPosition,
        BigDecimal encoreRatio,
        Integer recentCount5,
        Trend trend
) {

    public static PredictionResponse from(Prediction prediction) {
        Evidence evidence = EvidenceJson.parse(prediction.getEvidence());
        return new PredictionResponse(
                prediction.getRank(),
                prediction.getSongKey(),
                prediction.getSongName(),
                prediction.getProbability(),
                prediction.getPlayedCount(),
                prediction.getSampleSize(),
                prediction.getAvgPosition(),
                prediction.getEncoreRatio(),
                evidence != null ? evidence.recentCount5() : null,
                evidence != null ? evidence.trend() : null);
    }
}
