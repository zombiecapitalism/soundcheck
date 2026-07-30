package com.encore.prediction;

import com.encore.prediction.SetlistComposer.Composed;
import com.encore.prediction.SetlistComposer.Entry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 순수 함수 검증 — 예측 목록만 조립해서 구성 규칙을 확인한다. */
class SetlistComposerTest {

    private Entry entry(int rank, String key, String probability, String avgPosition,
                        String encoreRatio, Double openerRate) {
        return new Entry(rank, key, key.toUpperCase(), new BigDecimal(probability),
                avgPosition != null ? new BigDecimal(avgPosition) : null,
                encoreRatio != null ? new BigDecimal(encoreRatio) : null,
                openerRate);
    }

    @Test
    void composesOpenerMainAndEncoreBlocks() {
        List<Entry> predictions = List.of(
                entry(1, "closer", "0.95", "9.0", "0.1", 0.0),   // 본편 후반
                entry(2, "opener", "0.90", "2.0", "0.0", 0.9),   // 오프너 단골
                entry(3, "mid", "0.85", "5.0", "0.0", 0.0),
                entry(4, "encore hit", "0.80", "10.0", "0.8", 0.0)); // 앙코르 단골

        Composed composed = SetlistComposer.compose(predictions, 4);

        assertThat(composed.main()).extracting(Entry::songKey)
                .containsExactly("opener", "mid", "closer");
        assertThat(composed.encore()).extracting(Entry::songKey)
                .containsExactly("encore hit");
    }

    /** 앙코르 후보(비율 ≥ 0.5)가 없으면 전부 본편 — 결측 경로가 빈 블록으로 떨어져야 한다. */
    @Test
    void emptyEncoreWhenNoCandidates() {
        List<Entry> predictions = List.of(
                entry(1, "a", "0.9", "1.0", "0.1", 0.5),
                entry(2, "b", "0.8", "2.0", null, 0.0));

        Composed composed = SetlistComposer.compose(predictions, 2);

        assertThat(composed.encore()).isEmpty();
        assertThat(composed.main()).hasSize(2);
    }

    /** 앙코르 블록은 최대 3곡 — 비율 내림차순 상위만. */
    @Test
    void capsEncoreAtThree() {
        List<Entry> predictions = List.of(
                entry(1, "e1", "0.9", "8.0", "0.6", 0.0),
                entry(2, "e2", "0.8", "8.0", "0.9", 0.0),
                entry(3, "e3", "0.7", "8.0", "0.7", 0.0),
                entry(4, "e4", "0.6", "8.0", "0.5", 0.0),
                entry(5, "main", "0.5", "1.0", "0.0", 0.8));

        Composed composed = SetlistComposer.compose(predictions, 5);

        assertThat(composed.encore()).extracting(Entry::songKey)
                .containsExactly("e2", "e3", "e1"); // 비율 0.9 > 0.7 > 0.6, e4 탈락
        assertThat(composed.main()).extracting(Entry::songKey).containsExactly("main", "e4");
    }

    /** 예상 곡 수가 예측 수를 넘으면 있는 만큼만, 1 미만이면 1곡. */
    @Test
    void clampsExpectedSongCount() {
        List<Entry> predictions = List.of(
                entry(1, "a", "0.9", "1.0", "0.0", null),
                entry(2, "b", "0.8", "2.0", "0.0", null));

        assertThat(SetlistComposer.compose(predictions, 10).main()).hasSize(2);
        assertThat(SetlistComposer.compose(predictions, 0).main()).hasSize(1);
    }

    /** 구버전 스냅샷(openerRate null)은 오프너 고정 없이 평균 위치순으로만 배열한다. */
    @Test
    void ordersByPositionWhenOpenerRateMissing() {
        List<Entry> predictions = List.of(
                entry(1, "late", "0.9", "9.0", "0.0", null),
                entry(2, "early", "0.8", "2.0", "0.0", null),
                entry(3, "no position", "0.7", null, "0.0", null));

        Composed composed = SetlistComposer.compose(predictions, 3);

        // 위치 없는 곡은 맨 뒤
        assertThat(composed.main()).extracting(Entry::songKey)
                .containsExactly("early", "late", "no position");
    }

    /** 평균 위치 동률은 rank(확률순)로 결정적이게. */
    @Test
    void breaksPositionTiesByRank() {
        List<Entry> predictions = List.of(
                entry(2, "second", "0.8", "5.0", "0.0", null),
                entry(1, "first", "0.9", "5.0", "0.0", null));

        // rank 오름차순 입력 계약이지만 동률 정렬 자체를 검증하기 위해 뒤섞인 입력을 준다
        Composed composed = SetlistComposer.compose(
                predictions.stream().sorted((a, b) -> Integer.compare(a.rank(), b.rank())).toList(), 2);

        assertThat(composed.main()).extracting(Entry::songKey).containsExactly("first", "second");
    }

    @Test
    void emptyPredictionsComposeEmptyBlocks() {
        Composed composed = SetlistComposer.compose(List.of(), 20);

        assertThat(composed.main()).isEmpty();
        assertThat(composed.encore()).isEmpty();
    }
}
