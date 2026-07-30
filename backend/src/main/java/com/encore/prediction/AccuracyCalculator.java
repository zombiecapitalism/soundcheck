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
            BigDecimal f1,
            TopN top5,
            TopN top10,
            List<SongResult> results,
            List<Surprise> surprises) {
    }

    /** 상위 N곡 성적. 예측이 N곡보다 적으면 있는 만큼만 분모로 쓴다(size). */
    public record TopN(int size, int hits, BigDecimal accuracy) {
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

        BigDecimal precisionAtK = ratio(topKHits, topK);
        BigDecimal recall = ratio(totalHits, actualSongCount);
        return new AccuracyReport(
                actualSongCount,
                topK,
                topKHits,
                precisionAtK,
                totalHits,
                recall,
                f1(precisionAtK, recall),
                topN(results, 5),
                topN(results, 10),
                List.copyOf(results),
                List.copyOf(surprises));
    }

    /**
     * F1 = 2PR/(P+R). Precision@K는 예습 효율, Recall은 커버리지라 서로 반대로 움직일 수 있어
     * 조화 평균으로 한 줄 성적을 만든다. 둘 다 0이면 0 (0 나눗셈 방지).
     */
    static BigDecimal f1(BigDecimal precision, BigDecimal recall) {
        BigDecimal sum = precision.add(recall);
        if (sum.signum() == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return precision.multiply(recall).multiply(BigDecimal.TWO)
                .divide(sum, 4, RoundingMode.HALF_UP);
    }

    /** "상위 N곡만 예습했다면" — K(실제 곡 수)와 무관한 고정 창이라 이벤트 간 비교가 된다. */
    private static TopN topN(List<SongResult> results, int n) {
        int size = Math.min(n, results.size());
        int hits = 0;
        for (int i = 0; i < size; i++) {
            if (results.get(i).played()) {
                hits++;
            }
        }
        return new TopN(size, hits, ratio(hits, size));
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf((double) numerator / denominator).setScale(4, RoundingMode.HALF_UP);
    }
}
