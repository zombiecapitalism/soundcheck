package com.encore.prediction;

import com.encore.prediction.PredictionCalculator.Evidence;
import com.encore.prediction.PredictionCalculator.Trend;

import java.util.ArrayList;
import java.util.List;

/**
 * 예측 변화 추출(E4 요약부) — 순수 함수. LLM 프롬프트에 넣을 구조화 데이터를 만든다.
 * evidence가 없는 구버전 스냅샷 곡은 건너뛴다(변화를 판단할 근거가 없다).
 */
public final class TrendChanges {

    /** 그룹당 상한 — 프롬프트 길이를 묶고, rank 순 입력이라 확률 높은 곡이 먼저 잡힌다. */
    static final int MAX_PER_GROUP = 5;
    /** "이탈" 판정 최소 과거 등장률 — 원래 자주 하던 곡이 최근 5회에서 사라졌을 때만. */
    static final double DROPPED_MIN_FREQUENCY = 0.5;

    public record SongChange(String songName, int playedCount, int sampleSize, Integer recentCount5) {
    }

    public record Changes(
            List<SongChange> rising,
            List<SongChange> falling,
            List<SongChange> newcomers,
            List<SongChange> dropped) {

        public boolean isEmpty() {
            return rising.isEmpty() && falling.isEmpty() && newcomers.isEmpty() && dropped.isEmpty();
        }
    }

    private TrendChanges() {
    }

    /** predictionsByRank는 rank 오름차순(저장 순서). */
    public static Changes from(List<Prediction> predictionsByRank) {
        List<SongChange> rising = new ArrayList<>();
        List<SongChange> falling = new ArrayList<>();
        List<SongChange> newcomers = new ArrayList<>();
        List<SongChange> dropped = new ArrayList<>();

        for (Prediction prediction : predictionsByRank) {
            Evidence evidence = EvidenceJson.parse(prediction.getEvidence());
            if (evidence == null || evidence.recentCount5() == null) {
                continue;
            }
            SongChange change = new SongChange(prediction.getSongName(), prediction.getPlayedCount(),
                    prediction.getSampleSize(), evidence.recentCount5());
            double frequency = prediction.getSampleSize() > 0
                    ? (double) prediction.getPlayedCount() / prediction.getSampleSize()
                    : 0;

            // 신규 진입: 모든 등장이 최근 5회 안 — RISING과 겹치므로 먼저 분류하고 배타 처리
            if (evidence.recentCount5() > 0 && prediction.getPlayedCount() == evidence.recentCount5()
                    && prediction.getSampleSize() > 5) {
                add(newcomers, change);
            } else if (evidence.recentCount5() == 0 && frequency >= DROPPED_MIN_FREQUENCY) {
                // 이탈: 원래 자주 하던 곡이 최근 5회에서 사라짐 — FALLING보다 강한 신호
                add(dropped, change);
            } else if (evidence.trend() == Trend.RISING) {
                add(rising, change);
            } else if (evidence.trend() == Trend.FALLING) {
                add(falling, change);
            }
        }
        return new Changes(List.copyOf(rising), List.copyOf(falling),
                List.copyOf(newcomers), List.copyOf(dropped));
    }

    private static void add(List<SongChange> group, SongChange change) {
        if (group.size() < MAX_PER_GROUP) {
            group.add(change);
        }
    }
}
