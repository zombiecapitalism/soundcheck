package com.encore.prediction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 예상 셋리스트 구성(E6) — 순수 함수. 예측 목록에서 "실제 공연처럼 보이는 순서"를 만든다.
 * <p>
 * ① 확률 상위 expectedSongCount곡 선발 ② 앙코르 블록 = encoreRatio ≥ 0.5 곡(비율 내림차순, 최대 3)
 * ③ 오프너 = 본편 중 오프너 비율 최상위 곡 고정 ④ 나머지 본편 = 평균 위치 오름차순.
 * 저장하지 않고 조회 시 계산한다 — 예측이 이미 사전 계산돼 있어 가볍다.
 */
public final class SetlistComposer {

    /** 앙코르 블록 후보 기준 — 등장의 절반 이상이 앙코르였던 곡. */
    static final double ENCORE_THRESHOLD = 0.5;
    static final int MAX_ENCORE = 3;

    /**
     * 구성 입력 1곡. openerRate는 evidence positionStats에서 파생(오프너 등장/전체 등장) —
     * 구버전 스냅샷이면 null(오프너 고정 없이 평균 위치만 쓴다).
     */
    public record Entry(int rank, String songKey, String songName, BigDecimal probability,
                        BigDecimal avgPosition, BigDecimal encoreRatio, Double openerRate) {
    }

    public record Composed(List<Entry> main, List<Entry> encore) {
    }

    private SetlistComposer() {
    }

    /** predictionsByRank는 rank 오름차순(저장 순서). expectedSongCount는 1 미만이면 1로 본다. */
    public static Composed compose(List<Entry> predictionsByRank, int expectedSongCount) {
        if (predictionsByRank.isEmpty()) {
            return new Composed(List.of(), List.of());
        }
        int size = Math.min(Math.max(expectedSongCount, 1), predictionsByRank.size());
        List<Entry> selected = predictionsByRank.subList(0, size);

        // 앙코르 블록 — 비율 내림차순, 동률은 rank(확률순)로 결정적이게
        List<Entry> encore = selected.stream()
                .filter(entry -> entry.encoreRatio() != null
                        && entry.encoreRatio().doubleValue() >= ENCORE_THRESHOLD)
                .sorted(Comparator.comparing(Entry::encoreRatio).reversed()
                        .thenComparingInt(Entry::rank))
                .limit(MAX_ENCORE)
                .toList();

        List<Entry> mainPool = new ArrayList<>(selected);
        mainPool.removeAll(encore);

        // 오프너 고정 — 오프너 비율이 실제로 있는(>0) 곡 중 최상위. 없으면 고정 없이 위치순만
        Entry opener = mainPool.stream()
                .filter(entry -> entry.openerRate() != null && entry.openerRate() > 0)
                .max(Comparator.comparingDouble(Entry::openerRate)
                        .thenComparing(Comparator.comparingInt(Entry::rank).reversed()))
                .orElse(null);

        List<Entry> main = new ArrayList<>(mainPool.size());
        if (opener != null) {
            main.add(opener);
            mainPool.remove(opener);
        }
        mainPool.sort(Comparator
                .comparing(Entry::avgPosition, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(Entry::rank));
        main.addAll(mainPool);

        return new Composed(List.copyOf(main), List.copyOf(encore));
    }
}
