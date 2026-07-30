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

    /** F1은 Precision@K와 Recall의 조화 평균 — 두 지표가 반대로 움직일 때 한 줄 성적. */
    @Test
    void computesF1FromPrecisionAndRecall() {
        // computesPrecisionAtKAndRecall과 같은 상황: P=0.5, R=1.0 → F1 = 2·0.5·1.0/1.5 = 0.6667
        List<Prediction> predictions = List.of(
                prediction(1, "holy wars", "Holy Wars", "0.95"),
                prediction(2, "trust", "Trust", "0.80"),
                prediction(3, "sweating bullets", "Sweating Bullets", "0.60"));
        Show actual = actualShow(
                song("Holy Wars", "holy wars", 1, false),
                song("Sweating Bullets", "sweating bullets", 2, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.f1()).isEqualByComparingTo("0.6667");
    }

    /** P도 R도 0이면 F1도 0 — 0으로 나누지 않는다. */
    @Test
    void f1IsZeroWhenNothingHit() {
        List<Prediction> predictions = List.of(prediction(1, "a", "A", "0.9"));
        Show actual = actualShow(song("B", "b", 1, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.precisionAtK()).isEqualByComparingTo("0.0000");
        assertThat(report.recall()).isEqualByComparingTo("0.0000");
        assertThat(report.f1()).isEqualByComparingTo("0.0000");
    }

    /** Top-N은 실제 곡 수(K)와 무관한 고정 창 — 상위 5·10곡 예습 성적. */
    @Test
    void computesTopNAccuracy() {
        // 상위 5곡 중 1·3·5위 적중, 6위(적중)는 Top-5 밖
        List<Prediction> predictions = List.of(
                prediction(1, "s1", "S1", "0.9"), prediction(2, "s2", "S2", "0.8"),
                prediction(3, "s3", "S3", "0.7"), prediction(4, "s4", "S4", "0.6"),
                prediction(5, "s5", "S5", "0.5"), prediction(6, "s6", "S6", "0.4"));
        Show actual = actualShow(
                song("S1", "s1", 1, false), song("S3", "s3", 2, false),
                song("S5", "s5", 3, false), song("S6", "s6", 4, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.top5().size()).isEqualTo(5);
        assertThat(report.top5().hits()).isEqualTo(3);
        assertThat(report.top5().accuracy()).isEqualByComparingTo("0.6000");
        // 예측이 6곡뿐이라 Top-10 창은 6곡으로 줄고 분모도 6이다
        assertThat(report.top10().size()).isEqualTo(6);
        assertThat(report.top10().hits()).isEqualTo(4);
        assertThat(report.top10().accuracy()).isEqualByComparingTo("0.6667");
    }

    /** 예측이 N곡보다 적은 짧은 셋 — 있는 만큼만 분모로 쓰고 0으로 나누지 않는다. */
    @Test
    void topNClampsToPredictionCount() {
        List<Prediction> predictions = List.of(
                prediction(1, "a", "A", "0.9"), prediction(2, "b", "B", "0.8"));
        Show actual = actualShow(song("A", "a", 1, false));

        AccuracyReport report = AccuracyCalculator.evaluate(predictions, actual);

        assertThat(report.top5().size()).isEqualTo(2);
        assertThat(report.top5().hits()).isEqualTo(1);
        assertThat(report.top5().accuracy()).isEqualByComparingTo("0.5000");
        assertThat(report.top10().size()).isEqualTo(2);
    }

    @Test
    void rejectsSonglessActualSetlist() {
        List<Prediction> predictions = List.of(prediction(1, "a", "A", "0.9"));
        Show actual = actualShow(song("Intro Tape", "intro tape", 1, true));

        assertThatThrownBy(() -> AccuracyCalculator.evaluate(predictions, actual))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
