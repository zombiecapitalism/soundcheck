package com.encore.prediction;

import com.encore.artist.Artist;
import com.encore.prediction.TrendChanges.Changes;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 프롬프트 입력 구조화(순수 함수) 검증 — 요약 본문 자체는 수동 평가한다(PRD 8장). */
class TrendChangesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Artist artist = Artist.builder().mbid(UUID.randomUUID()).name("Megadeth").build();
    private final TargetEvent event = TargetEvent.builder()
            .artist(artist).eventName("이벤트").eventDate(LocalDate.of(2026, 10, 2))
            .expectedShowType(ShowType.FESTIVAL).build();

    private Prediction prediction(int rank, String name, int playedCount, int sampleSize,
                                  Integer recentCount5, PredictionCalculator.Trend trend) {
        String evidence = null;
        if (recentCount5 != null) {
            evidence = MAPPER.writeValueAsString(new PredictionCalculator.Evidence(
                    0.95, 1.5, 1, 1, (double) playedCount / sampleSize,
                    0.5, recentCount5, trend, null, null, List.of()));
        }
        return Prediction.builder()
                .targetEvent(event).songKey(name.toLowerCase()).songName(name)
                .probability(new BigDecimal("0.5000")).rank((short) rank)
                .playedCount((short) playedCount).sampleSize((short) sampleSize)
                .evidence(evidence)
                .build();
    }

    @Test
    void classifiesNewcomersDroppedRisingFalling() {
        Changes changes = TrendChanges.from(List.of(
                // 모든 등장이 최근 5회 안 → 신규 진입 (RISING이어도 중복 분류 없음)
                prediction(1, "New Song", 3, 20, 3, PredictionCalculator.Trend.RISING),
                // 자주 하던 곡(12/20)이 최근 5회 미등장 → 이탈
                prediction(2, "Gone Song", 12, 20, 0, PredictionCalculator.Trend.FALLING),
                // 일반 상승세
                prediction(3, "Up Song", 10, 20, 4, PredictionCalculator.Trend.RISING),
                // 일반 하락세 (등장률 0.25 < 0.5라 이탈 아님)
                prediction(4, "Down Song", 5, 20, 0, PredictionCalculator.Trend.FALLING),
                // 변화 없음
                prediction(5, "Stable Song", 10, 20, 2, PredictionCalculator.Trend.STABLE)));

        assertThat(changes.newcomers()).extracting(TrendChanges.SongChange::songName)
                .containsExactly("New Song");
        assertThat(changes.dropped()).extracting(TrendChanges.SongChange::songName)
                .containsExactly("Gone Song");
        assertThat(changes.rising()).extracting(TrendChanges.SongChange::songName)
                .containsExactly("Up Song");
        assertThat(changes.falling()).extracting(TrendChanges.SongChange::songName)
                .containsExactly("Down Song");
        assertThat(changes.isEmpty()).isFalse();
    }

    /** 구버전 evidence(확장 필드 없음)나 evidence 없는 곡은 판단 근거가 없어 건너뛴다. */
    @Test
    void skipsSongsWithoutEvidence() {
        Changes changes = TrendChanges.from(List.of(
                prediction(1, "Legacy Song", 10, 20, null, null)));

        assertThat(changes.isEmpty()).isTrue();
    }

    /** 그룹당 5곡 상한 — rank 순 입력이라 확률 높은 곡이 잡힌다. */
    @Test
    void capsEachGroupAtFive() {
        List<Prediction> predictions = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            predictions.add(prediction(i, "Up " + i, 10, 20, 4, PredictionCalculator.Trend.RISING));
        }

        Changes changes = TrendChanges.from(predictions);

        assertThat(changes.rising()).hasSize(5);
        assertThat(changes.rising().getFirst().songName()).isEqualTo("Up 1");
    }

    /** 프롬프트 계약: 제약 문구와 통계 수치가 항상 포함된다. */
    @Test
    void promptContainsConstraintsAndNumbers() {
        Changes changes = TrendChanges.from(List.of(
                prediction(1, "New Song", 3, 20, 3, PredictionCalculator.Trend.RISING)));

        assertThat(TrendSummaryPrompts.system()).contains("추측하거나 지어내지 않는다");
        String user = TrendSummaryPrompts.user("Megadeth", changes);
        assertThat(user).contains("Megadeth").contains("New Song")
                .contains("최근 20회 중 3회").contains("최근 5회 중 3회");
    }
}
