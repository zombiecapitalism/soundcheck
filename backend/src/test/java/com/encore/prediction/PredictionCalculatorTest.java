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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        // s1에서 2번째, s2에서 5번째(앙코르) → 평균 3.5
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("A", "a", 1), song("Closer", "closer", 2)),
                show("s2", LocalDate.of(2026, 7, 2), ShowType.UNKNOWN,
                        song("A", "a", 1), song("B", "b", 2), song("C", "c", 3), song("D", "d", 4),
                        song("Closer", "closer", 5, true, false))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        SongScore closer = find(scores, "closer");
        assertThat(closer.avgPosition()).isEqualByComparingTo("3.5");
        assertThat(closer.encoreRatio()).isEqualByComparingTo("0.5000");
    }

    /** 평균 위치는 실연주 순번 기준 — 인트로 테이프가 있어도 "보통 1번째 곡"이어야 한다(D10). */
    @Test
    void avgPositionIgnoresTapeOffset() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("Intro Tape", "intro tape", 1, false, true),
                        song("Opener", "opener", 2))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        assertThat(find(scores, "opener").avgPosition()).isEqualByComparingTo("1.0");
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

    /** 부스트 기여 분해(E1): boost가 1.0이면 부스트 없는 확률과 실제 확률이 같아야 한다. */
    @Test
    void unboostedProbabilityEqualsProbabilityWithoutBoost() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("fest", LocalDate.of(2026, 7, 2), ShowType.FESTIVAL, song("A", "a", 1)),
                show("solo", LocalDate.of(2026, 7, 1), ShowType.SOLO, song("B", "b", 1))
        ), ShowType.FESTIVAL, new Params(0.5, 1.0));

        SongScore a = find(scores, "a");
        assertThat(a.evidence().unboostedProbability())
                .isCloseTo(a.probability().doubleValue(), org.assertj.core.data.Offset.offset(1e-4));
    }

    /** 부스트가 있으면 유형 일치 곡의 확률은 부스트 없는 확률보다 커야 한다(E1 근거 카드의 분해). */
    @Test
    void boostRaisesProbabilityAboveUnboosted() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("fest", LocalDate.of(2026, 7, 2), ShowType.FESTIVAL, song("A", "a", 1)),
                show("solo", LocalDate.of(2026, 7, 1), ShowType.SOLO, song("B", "b", 1))
        ), ShowType.FESTIVAL, new Params(1.0, 2.0));

        // A: 확률 2/3, 부스트 없으면 1/2
        SongScore a = find(scores, "a");
        assertThat(a.probability()).isEqualByComparingTo("0.6667");
        assertThat(a.evidence().unboostedProbability())
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** E4: 최근 5회 창은 표본 최근순 인덱스 0~4의 등장만 센다. */
    @Test
    void countsAppearancesInRecentFiveShows() {
        List<Show> shows = new ArrayList<>();
        // 7회 공연: 곡은 최근 1·2번째와 가장 오래된 공연에만 등장
        for (int i = 0; i < 7; i++) {
            LocalDate date = LocalDate.of(2026, 7, 20 - i); // i=0이 최근
            boolean plays = i == 0 || i == 1 || i == 6;
            shows.add(plays
                    ? show("s" + i, date, ShowType.UNKNOWN, song("Hit", "hit", 1), song("Filler", "filler" + i, 2))
                    : show("s" + i, date, ShowType.UNKNOWN, song("Filler", "filler" + i, 1)));
        }
        List<SongScore> scores = PredictionCalculator.calculate(shows, ShowType.FESTIVAL, NO_WEIGHTING);

        assertThat(find(scores, "hit").evidence().recentCount5()).isEqualTo(2);
    }

    /** E4 추이: 최근 절반에만 나오면 RISING, 이전 절반에만 나오면 FALLING, 고르면 STABLE. */
    @Test
    void classifiesTrendByHalfComparison() {
        List<Show> shows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            LocalDate date = LocalDate.of(2026, 7, 20 - i);
            List<ShowSong> songs = new ArrayList<>();
            songs.add(song("Filler", "filler" + i, 1));
            if (i < 5) {
                songs.add(song("New Hit", "new hit", 2)); // 최근 절반에만
            } else {
                songs.add(song("Dropped", "dropped", 2)); // 이전 절반에만
            }
            songs.add(song("Staple", "staple", 3)); // 매회
            shows.add(show("s" + i, date, ShowType.UNKNOWN, songs.toArray(ShowSong[]::new)));
        }
        List<SongScore> scores = PredictionCalculator.calculate(shows, ShowType.FESTIVAL, NO_WEIGHTING);

        assertThat(find(scores, "new hit").evidence().trend())
                .isEqualTo(PredictionCalculator.Trend.RISING);
        assertThat(find(scores, "dropped").evidence().trend())
                .isEqualTo(PredictionCalculator.Trend.FALLING);
        assertThat(find(scores, "staple").evidence().trend())
                .isEqualTo(PredictionCalculator.Trend.STABLE);
    }

    /** 표본이 홀수(3회)여도 전·후반이 나뉜다: 최근 1회 vs 이전 2회. 표본 1회면 STABLE. */
    @Test
    void splitsOddAndTinySamplesForTrend() {
        List<SongScore> odd = PredictionCalculator.calculate(List.of(
                show("s0", LocalDate.of(2026, 7, 3), ShowType.UNKNOWN, song("Hit", "hit", 1)),
                show("s1", LocalDate.of(2026, 7, 2), ShowType.UNKNOWN, song("Filler", "f1", 1)),
                show("s2", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Filler", "f2", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);
        // 최근 절반(1회) 등장률 1.0 vs 이전 절반(2회) 0.0 → RISING
        assertThat(find(odd, "hit").evidence().trend()).isEqualTo(PredictionCalculator.Trend.RISING);

        List<SongScore> single = PredictionCalculator.calculate(List.of(
                show("only", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Hit", "hit", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);
        assertThat(find(single, "hit").evidence().trend()).isEqualTo(PredictionCalculator.Trend.STABLE);
    }

    /** E3 위치 구간: 본편 1번째는 오프너, 나머지는 본편 3분위, 앙코르는 별도 버킷. */
    @Test
    void classifiesSetlistPositions() {
        // 본편 6곡 + 앙코르 1곡: 오프너(1) / 초반(2) / 중반(3·4) / 후반(5·6) / 앙코르
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("P1", "p1", 1), song("P2", "p2", 2), song("P3", "p3", 3),
                        song("P4", "p4", 4), song("P5", "p5", 5), song("P6", "p6", 6),
                        song("Enc", "enc", 7, true, false))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        assertThat(find(scores, "p1").evidence().positionStats().opener()).isEqualTo(1);
        assertThat(find(scores, "p2").evidence().positionStats().early()).isEqualTo(1);
        assertThat(find(scores, "p3").evidence().positionStats().mid()).isEqualTo(1);
        assertThat(find(scores, "p4").evidence().positionStats().mid()).isEqualTo(1);
        assertThat(find(scores, "p5").evidence().positionStats().late()).isEqualTo(1);
        assertThat(find(scores, "p6").evidence().positionStats().late()).isEqualTo(1);
        assertThat(find(scores, "enc").evidence().positionStats().encore()).isEqualTo(1);
    }

    /** E3 경계: 본편 1·2·3곡짜리 공연의 3분위. tape 곡은 순번 계산에서도 빠진다. */
    @Test
    void classifiesPositionsInTinyMainSets() {
        // 본편 1곡 → 오프너
        List<SongScore> one = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("A", "a", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);
        assertThat(find(one, "a").evidence().positionStats().opener()).isEqualTo(1);

        // 본편 2곡 → 오프너 + 중반(ceil(2/3)=1 초과, ceil(4/3)=2 이내)
        List<SongScore> two = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("A", "a", 1), song("B", "b", 2))
        ), ShowType.FESTIVAL, NO_WEIGHTING);
        assertThat(find(two, "b").evidence().positionStats().mid()).isEqualTo(1);

        // tape 곡이 앞에 있어도 실연주 순번 기준이라 A가 오프너다
        List<SongScore> tapeFirst = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("Intro", "intro", 1, false, true), song("A", "a", 2), song("B", "b", 3),
                        song("C", "c", 4))
        ), ShowType.FESTIVAL, NO_WEIGHTING);
        assertThat(find(tapeFirst, "a").evidence().positionStats().opener()).isEqualTo(1);
        assertThat(find(tapeFirst, "c").evidence().positionStats().late()).isEqualTo(1);
    }

    /** 앙코르에만 나오는 곡은 본편 버킷이 전부 0이다. */
    @Test
    void encoreOnlySongHasOnlyEncoreBucket() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("s1", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN,
                        song("Main", "main", 1), song("Enc", "enc", 2, true, false)),
                show("s2", LocalDate.of(2026, 7, 2), ShowType.UNKNOWN,
                        song("Main", "main", 1), song("Enc", "enc", 2, true, false))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        var stats = find(scores, "enc").evidence().positionStats();
        assertThat(stats.encore()).isEqualTo(2);
        assertThat(stats.opener() + stats.early() + stats.mid() + stats.late()).isZero();
    }

    /** E4 유형별 등장: UNKNOWN 공연은 페스티벌/단독 어느 분모에도 들어가지 않는다. */
    @Test
    void breaksDownAppearancesByShowType() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("f1", LocalDate.of(2026, 7, 4), ShowType.FESTIVAL, song("Hit", "hit", 1)),
                show("f2", LocalDate.of(2026, 7, 3), ShowType.FESTIVAL, song("Filler", "filler", 1)),
                show("solo", LocalDate.of(2026, 7, 2), ShowType.SOLO, song("Hit", "hit", 1)),
                show("unknown", LocalDate.of(2026, 7, 1), ShowType.UNKNOWN, song("Hit", "hit", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        var breakdown = find(scores, "hit").evidence().typeBreakdown();
        assertThat(breakdown.festivalShows()).isEqualTo(2);
        assertThat(breakdown.festivalPlayed()).isEqualTo(1);
        assertThat(breakdown.soloShows()).isEqualTo(1);
        assertThat(breakdown.soloPlayed()).isEqualTo(1);
    }

    /** 유형 편중 표본(전부 FESTIVAL): 단독 분모가 0이라는 사실이 그대로 보여야 한다(0 나눗셈은 응답층 책임). */
    @Test
    void typeBreakdownWithFestivalOnlySample() {
        List<SongScore> scores = PredictionCalculator.calculate(List.of(
                show("f1", LocalDate.of(2026, 7, 1), ShowType.FESTIVAL, song("Hit", "hit", 1))
        ), ShowType.FESTIVAL, NO_WEIGHTING);

        var breakdown = find(scores, "hit").evidence().typeBreakdown();
        assertThat(breakdown.soloShows()).isZero();
        assertThat(breakdown.soloPlayed()).isZero();
    }

    @Test
    void returnsEmptyForNoShows() {
        assertThat(PredictionCalculator.calculate(List.of(), ShowType.FESTIVAL, NO_WEIGHTING)).isEmpty();
    }

    /** boost=0이면 totalWeight가 0이 되어 NaN으로 터질 수 있다 — 퇴화 파라미터는 생성 시점에 거부. */
    @Test
    void rejectsDegenerateParams() {
        assertThatThrownBy(() -> new Params(0.0, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Params(1.1, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Params(-0.5, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Params(0.95, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Params(0.95, -1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
