package com.encore.api;

import com.encore.prediction.Prediction;

import java.math.BigDecimal;

/**
 * GET /api/events/{id}/predictions 항목 — 배치가 사전 계산한 값을 그대로 옮긴다(F5: 근거 수치 노출).
 * "최근 sampleSize회 중 playedCount회 연주"가 화면에 그대로 나가는 근거다.
 */
public record PredictionResponse(
        int rank,
        String songKey,
        String songName,
        BigDecimal probability,
        int playedCount,
        int sampleSize,
        BigDecimal avgPosition,
        BigDecimal encoreRatio
) {

    public static PredictionResponse from(Prediction prediction) {
        return new PredictionResponse(
                prediction.getRank(),
                prediction.getSongKey(),
                prediction.getSongName(),
                prediction.getProbability(),
                prediction.getPlayedCount(),
                prediction.getSampleSize(),
                prediction.getAvgPosition(),
                prediction.getEncoreRatio());
    }
}
