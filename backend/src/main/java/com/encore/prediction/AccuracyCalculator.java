package com.encore.prediction;

import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 예측 vs 실제 셋리스트 비교 — 순수 함수.
 * <p>
 * 핵심 지표는 Precision@K (K = 실제 연주 곡 수): "상위 K곡을 예습했다면 몇 곡을 맞췄나".
 * 예측 목록은 실제보다 길 수밖에 없으므로(레퍼토리 전체) 전체 정밀도는 의미가 없고,
 * 사용자가 실제로 예습하는 분량인 상위 K가 기준이어야 한다. 보조로 Recall(실연주 곡 중
 * 예측 목록에 있던 비율)과 서프라이즈(예측 밖 실연주 곡)를 함께 낸다.
 */
public final class AccuracyCalculator {

    public record SongResult(int rank, String songKey, String songName, BigDecimal probability,
                             boolean played, Integer actualPosition) {
    }

    /** 예측 목록에 아예 없던 실연주 곡 — 로테이션 감지 실패 사례라 그 자체로 콘텐츠다. */
    public record Surprise(String songName, int actualPosition) {
    }

    public record AccuracyReport(
            int actualSongCount,
            int topK,
            int topKHits,
            BigDecimal precisionAtK,
            int totalHits,
            BigDecimal recall,
            List<SongResult> results,
            List<Surprise> surprises) {
    }

    private AccuracyCalculator() {
    }

    /** predictions는 rank 오름차순이어야 한다(저장 순서 그대로). */
    public static AccuracyReport evaluate(List<Prediction> predictions, Show actual) {
        // tape 제외 실연주 곡, 같은 곡 재등장(리프라이즈)은 첫 위치만 — 예측 집계와 같은 규칙
        Map<String, Short> actualByKey = new LinkedHashMap<>();
        for (ShowSong song : actual.playedSongs()) {
            actualByKey.putIfAbsent(song.getSongKey(), song.getPositionTotal());
        }
        if (actualByKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "실연주 곡이 없는 셋리스트로는 적중률을 계산할 수 없습니다: " + actual.getSetlistId());
        }

        int actualSongCount = actualByKey.size();
        int topK = Math.min(actualSongCount, predictions.size());

        List<SongResult> results = new ArrayList<>(predictions.size());
        int topKHits = 0;
        int totalHits = 0;
        for (int i = 0; i < predictions.size(); i++) {
            Prediction prediction = predictions.get(i);
            Short actualPosition = actualByKey.get(prediction.getSongKey());
            boolean played = actualPosition != null;
            if (played) {
                totalHits++;
                if (i < topK) {
                    topKHits++;
                }
            }
            results.add(new SongResult(prediction.getRank(), prediction.getSongKey(),
                    prediction.getSongName(), prediction.getProbability(), played,
                    played ? actualPosition.intValue() : null));
        }

        List<String> predictedKeys = predictions.stream().map(Prediction::getSongKey).toList();
        List<Surprise> surprises = new ArrayList<>();
        for (ShowSong song : actual.playedSongs()) {
            if (!predictedKeys.contains(song.getSongKey())
                    && actualByKey.get(song.getSongKey()) == song.getPositionTotal()) {
                surprises.add(new Surprise(song.getSongName(), song.getPositionTotal()));
            }
        }
        surprises.sort(Comparator.comparingInt(Surprise::actualPosition));

        return new AccuracyReport(
                actualSongCount,
                topK,
                topKHits,
                ratio(topKHits, topK),
                totalHits,
                ratio(totalHits, actualSongCount),
                List.copyOf(results),
                List.copyOf(surprises));
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf((double) numerator / denominator).setScale(4, RoundingMode.HALF_UP);
    }
}
