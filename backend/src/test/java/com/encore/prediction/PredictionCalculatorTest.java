package com.encore.prediction;

import com.encore.artist.Artist;
import com.encore.prediction.PredictionCalculator.Params;
import com.encore.prediction.PredictionCalculator.SongScore;
import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 순수 함수 검증 — Spring/DB 없이 엔티티만 조립해서 계산한다. */
class PredictionCalculatorTest {

    private static final Params NO_WEIGHTING = new Params(1.0, 1.0);

    private final Artist artist = Artist.builder().mbid(UUID.randomUUID()).name("Megadeth").build();

    private Show show(String id, LocalDate date, ShowType type, ShowSong... songs) {
        Show show = Show.builder()
                .setlistId(id).versionId("v1").artist(artist).eventDate(date)
                .showType(type).rawJson("{}")
                .build();
        show.replaceSongs(new ArrayList<>(List.of(songs)));
        return show;
    }

    private ShowSong song(String name, String key, int positionTotal, boolean encore, boolean tape) {
        return ShowSong.builder()
                .setIndex((short) (encore ? 1 : 0)).encore(encore)
                .positionInSet((short) positionTotal).positionTotal((short) positionTotal)
                .songName(name).songKey(key).tape(tape)
                .build();
    }

    private ShowSong song(String name, String key, int positionTotal) {
        return song(name, key, positionTotal, false, false);
    }

    private static SongScore find(List<SongScore> scores, String key) {
        return scores.stream().filter(s -> s.songKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void computesBaseFrequencyWithoutWeighting() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Holy Wars", "holy wars", 1)),
                show("s2", LocalDate.of(2026, 7, 2), ShowType.UNKNOWN, song("Holy Wars", "holy wars", 1)),
                show("s3", LocalDate.of(2026, 7, 3), ShowType.UNKNOWN, song("Trust", "trust", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        SongScore holyWars = find(scores, "holy wars");
        assertThat(holyWars.probability()).isEqualByComparingTo("0.6667"); // 2/3
        assertThat(holyWars.playedCount()).isEqualTo(2);
        assertThat(holyWars.sampleSize()).isEqualTo(3);
        assertThat(holyWars.evidence().baseFrequency()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    /**
     * 곡 0건 공연은 표본에서 빠져야 한다(docs 1.4). setlist.fm은 공연 전에 페이지가 먼저
     * 생기므로 빈 셋리스트가 최근순 맨 앞에 오는 게 보통이다 — 포함되면 전 곡이 희석된다.
     */
    @Test
    void excludesSonglessShowsFromSample() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("upcoming", LocalDate.of(2026, 7, 30), ShowType.FESTIVAL), // 등록만 된 미래 공연
                show("tapeOnly", LocalDate.of(2026, 7, 20), ShowType.UNKNOWN,
                        song("Intro Tape", "intro tape", 1, false, true)),
                show("played", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Holy Wars", "holy wars", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        SongScore holyWars = find(scores, "holy wars");
        assertThat(holyWars.sampleSize()).isEqualTo(1);
        assertThat(holyWars.probability()).isEqualByComparingTo("1.0000"); // 빈 공연이 희석하지 않는다
    }

    @Test
    void excludesTapeSongsFromAggregation() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("Intro Tape", "intro tape", 1, false, true),
                        song("Holy Wars", "holy wars", 2))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        assertThat(scores).extracting(SongScore::songKey).containsExactly("holy wars");
    }

    /** 감쇠 0.5, 2회 공연: 최근 공연만 나온 곡이 이전 공연만 나온 곡보다 높아야 한다. */
    @Test
    void weighsRecentShowsHigher() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("old1", LocalDate.of(2026, 6, 1), ShowType.UNKNOWN, song("Old Song", "old song", 1)),
                show("new1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("New Song", "new song", 1))
        ), ShowType.FESTIVAL, new Params(0.5, 1.0));

        // 가중치: new1=1.0, old1=0.5, 합 1.5
        assertThat(find(scores, "new song").probability()).isEqualByComparingTo("0.6667");
        assertThat(find(scores, "old song").probability()).isEqualByComparingTo("0.3333");
        assertThat(scores.getFirst().songKey()).isEqualTo("new song");
    }

    /** 예측 대상이 FESTIVAL이면 과거 FESTIVAL 셋의 곡이 더 높은 확률을 받는다. */
    @Test
    void boostsMatchingShowType() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("fest", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL, song("Festival Song", "festival song", 1)),
                show("solo", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Club Song", "club song", 1))
        ), ShowType.FESTIVAL, new Params(1.0, 2.0));

        // 가중치: fest=2.0, solo=1.0, 합 3.0
        assertThat(find(scores, "festival song").probability()).isEqualByComparingTo("0.6667");
        assertThat(find(scores, "club song").probability()).isEqualByComparingTo("0.3333");
    }

    @Test
    void computesAveragePositionAndEncoreRatio() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Closer", "closer", 2)),
                show("s2", LocalDate.of(2026, 7, 2), ShowType.UNKNOWN, song("Closer", "closer", 5, true, false))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        SongScore closer = find(scores, "closer");
        assertThat(closer.avgPosition()).isEqualByComparingTo("3.5");
        assertThat(closer.encoreRatio()).isEqualByComparingTo("0.5000");
    }

    /** 항상 연주된 곡은 정확히 1.0000 — prediction 테이블 CHECK(≤1)의 경계값. */
    @Test
    void alwaysPlayedSongScoresExactlyOne() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL, song("Anthem", "anthem", 1)),
                show("s2", LocalDate.of(2026, 7, 2), ShowType.UNKNOWN, song("Anthem", "anthem", 1))
        ), ShowType.FESTIVAL, new Params(0.9, 1.5));

        assertThat(find(scores, "anthem").probability()).isEqualByComparingTo(BigDecimal.ONE);
    }

    /** 같은 곡이 한 공연에 두 번 나오면(리프라이즈) 등장 1회로 세고 첫 위치를 쓴다. */
    @Test
    void countsDuplicateSongInOneShowOnce() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("Opener", "opener", 1),
                        song("Opener (Reprise)", "opener", 9))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        SongScore opener = find(scores, "opener");
        assertThat(opener.playedCount()).isEqualTo(1);
        assertThat(opener.probability()).isEqualByComparingTo("1.0000");
        assertThat(opener.avgPosition()).isEqualByComparingTo("1.0");
    }

    /** 정규화는 손실 변환 — 화면 표기는 가장 최근 공연의 원본 곡명을 쓴다. */
    @Test
    void usesMostRecentOriginalSongName() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("old1", LocalDate.of(2026, 6, 1), ShowType.UNKNOWN, song("BAT COUNTRY!!", "bat country", 1)),
                show("new1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Bat Country", "bat country", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        assertThat(find(scores, "bat country").songName()).isEqualTo("Bat Country");
    }

    /** 동률이면 연주 횟수, 그다음 song_key 순 — 실행마다 순위가 흔들리면 안 된다. */
    @Test
    void breaksTiesDeterministically() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("Zebra", "zebra", 1), song("Alpha", "alpha", 2))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        assertThat(scores).extracting(SongScore::songKey).containsExactly("alpha", "zebra");
    }

    @Test
    void recordsAppearancesInEvidence() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 6, 1), ShowType.UNKNOWN, song("Hit", "hit", 3)),
                show("s2", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL, song("Hit", "hit", 1))
        ), ShowType.FESTIVAL, new Params(0.5, 2.0));

        var evidence = find(scores, "hit").evidence();
        assertThat(evidence.appearances()).hasSize(2);
        // 최근 공연(s2)이 인덱스 0: 가중치 = 0.5^0 × 2.0(FESTIVAL 일치) = 2.0
        var recent = evidence.appearances().stream()
                .filter(a -> a.setlistId().equals("s2")).findFirst().orElseThrow();
        assertThat(recent.weight()).isEqualTo(2.0);
        assertThat(recent.eventDate()).isEqualTo("2026-07-01");
        assertThat(evidence.totalWeight()).isEqualTo(2.5); // 2.0 + 0.5^1×1.0
        assertThat(evidence.weightedScore()).isEqualTo(2.5);
    }

    @Test
    void returnsEmptyForNoShows() {
        assertThat(PredictionCalculator.calculate(List.of(), ShowType.FESTIVAL, NO_WEIGHTING)).isEmpty();
    }
}
