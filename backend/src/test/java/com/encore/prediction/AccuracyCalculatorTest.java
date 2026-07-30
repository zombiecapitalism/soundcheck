package com.encore.prediction;

import com.encore.artist.Artist;
import com.encore.prediction.AccuracyCalculator.AccuracyReport;
import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 순수 함수 검증 — DB 없이 예측 목록과 실제 공연만 조립해서 비교한다. */
class AccuracyCalculatorTest {

    private final Artist artist = Artist.builder().mbid(UUID.randomUUID()).name("Megadeth").build();
    private final TargetEvent event = TargetEvent.builder()
            .artist(artist).eventName("검증 이벤트").eventDate(LocalDate.of(2026, 8, 1))
            .expectedShowType(ShowType.FESTIVAL).build();

    private Prediction prediction(int rank, String key, String name, String probability) {
        return Prediction.builder()
                .targetEvent(event).songKey(key).songName(name)
                .probability(new BigDecimal(probability))
                .rank((short) rank).playedCount((short) 10).sampleSize((short) 20)
                .build();
    }

    private ShowSong song(String name, String key, int position, boolean tape) {
        return ShowSong.builder()
                .setIndex((short) 0).positionInSet((short) position).positionTotal((short) position)
                .songName(name).songKey(key).tape(tape)
                .build();
    }

    private Show actualShow(ShowSong... songs) {
        Show show = Show.builder()
                .setlistId("actual1").versionId("v1").artist(artist)
                .eventDate(LocalDate.of(2026, 8, 1)).showType(ShowType.FESTIVAL).rawJson("{}")
                .build();
        show.replaceSongs(List.of(songs));
        return show;
    }

    @Test
    void computesPrecisionAtKAndRecall() {
        // 실제 2곡 → K=2. 상위 2 예측 중 1곡 적중, 전체 예측(3곡)으로는 2곡 적중
        List<Prediction> predictions = List.of(
                prediction(1, "holy wars", "Holy Wars", "0.95"),
                prediction(2, "trust", "Trust", "0.80"),
                prediction(3, "sweating bullets", "Sweating Bullets", "0.60"));
        Show actual = actualShow(
                song("Holy Wars", "holy wars", 1, false),
                song("Sweating Bullets", "sweating bullets", 2, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.actualSongCount()).isEqualTo(2);
        assertThat(report.topK()).isEqualTo(2);
        assertThat(report.topKHits()).isEqualTo(1);
        assertThat(report.precisionAtK()).isEqualByComparingTo("0.5000");
        assertThat(report.totalHits()).isEqualTo(2);
        assertThat(report.recall()).isEqualByComparingTo("1.0000");
        assertThat(report.results()).extracting(AccuracyCalculator.SongResult::played)
                .containsExactly(true, false, true);
        assertThat(report.results().getFirst().actualPosition()).isEqualTo(1);
    }

    @Test
    void perfectPredictionScoresOne() {
        List<Prediction> predictions = List.of(
                prediction(1, "a", "A", "0.9"), prediction(2, "b", "B", "0.8"));
        Show actual = actualShow(song("A", "a", 1, false), song("B", "b", 2, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.precisionAtK()).isEqualByComparingTo("1.0000");
        assertThat(report.recall()).isEqualByComparingTo("1.0000");
        assertThat(report.surprises()).isEmpty();
    }

    /** 예측 목록에 없던 실연주 곡은 서프라이즈로 잡혀야 한다 — 로테이션 감지 실패 사례. */
    @Test
    void reportsUnpredictedSongsAsSurprises() {
        List<Prediction> predictions = List.of(prediction(1, "holy wars", "Holy Wars", "0.95"));
        Show actual = actualShow(
                song("Holy Wars", "holy wars", 1, false),
                song("Rust in Peace... Polaris", "rust in peace polaris", 2, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.surprises()).hasSize(1);
        assertThat(report.surprises().getFirst().songName()).isEqualTo("Rust in Peace... Polaris");
        assertThat(report.recall()).isEqualByComparingTo("0.5000");
    }

    /** 실제 셋의 tape 곡은 정답에서 제외 — 예측 집계와 같은 규칙이어야 공정하다. */
    @Test
    void excludesTapeSongsFromActual() {
        List<Prediction> predictions = List.of(prediction(1, "holy wars", "Holy Wars", "0.95"));
        Show actual = actualShow(
                song("Intro Tape", "intro tape", 1, true),
                song("Holy Wars", "holy wars", 2, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.actualSongCount()).isEqualTo(1);
        assertThat(report.precisionAtK()).isEqualByComparingTo("1.0000");
        assertThat(report.surprises()).isEmpty();
    }

    /** 같은 곡 재등장(리프라이즈)은 1곡으로 세고 첫 위치를 쓴다. */
    @Test
    void countsRepriseOnce() {
        List<Prediction> predictions = List.of(prediction(1, "opener", "Opener", "0.9"));
        Show actual = actualShow(
                song("Opener", "opener", 1, false),
                song("Opener (Reprise)", "opener", 5, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.actualSongCount()).isEqualTo(1);
        assertThat(report.results().getFirst().actualPosition()).isEqualTo(1);
        assertThat(report.surprises()).isEmpty();
    }

    /** 예측 곡 수가 실제보다 적으면 K는 예측 수로 줄어든다 — 0으로 나누지 않는다. */
    @Test
    void clampsKToPredictionCount() {
        List<Prediction> predictions = List.of(prediction(1, "a", "A", "0.9"));
        Show actual = actualShow(
                song("A", "a", 1, false), song("B", "b", 2, false), song("C", "c", 3, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.topK()).isEqualTo(1);
        assertThat(report.precisionAtK()).isEqualByComparingTo("1.0000");
        assertThat(report.recall()).isEqualByComparingTo("0.3333");
    }

    @Test
    void rejectsSonglessActualSetlist() {
        List<Prediction> predictions = List.of(prediction(1, "a", "A", "0.9"));
        Show actual = actualShow(song("Intro Tape", "intro tape", 1, true));

        assertThatThrownBy(() -> AccuracyCalculator.evaluate(predictions, actual))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
