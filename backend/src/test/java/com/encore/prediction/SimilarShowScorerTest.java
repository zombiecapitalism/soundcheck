package com.encore.prediction;

import com.encore.artist.Artist;
import com.encore.prediction.SimilarShowScorer.ScoredShow;
import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/** 순수 함수 검증 — 유형·시기·곡 구성 겹침 점수와 결정적 정렬. */
class SimilarShowScorerTest {

    private static final LocalDate EVENT = LocalDate.of(2026, 10, 2);

    private final Artist artist = Artist.builder().mbid(UUID.randomUUID()).name("Megadeth").build();

    private Show show(String id, LocalDate date, ShowType type, String... songKeys) {
        Show show = Show.builder()
                .setlistId(id).versionId("v1").artist(artist).eventDate(date)
                .showType(type).rawJson("{}")
                .build();
        show.replaceSongs(java.util.stream.IntStream.range(0, songKeys.length)
                .mapToObj(i -> ShowSong.builder()
                        .setIndex((short) 0).positionInSet((short) (i + 1)).positionTotal((short) (i + 1))
                        .songName(songKeys[i]).songKey(songKeys[i])
                        .build())
                .map(ShowSong.class::cast)
                .toList());
        return show;
    }

    @Test
    void combinesTypeRecencyAndOverlap() {
        // 같은 날짜·같은 셋 — 유형만 다르면 점수 차이는 정확히 TYPE_WEIGHT
        Show festival = show("fest", EVENT.minusDays(30), ShowType.FESTIVAL, "a", "b");
        Show solo = show("solo", EVENT.minusDays(30), ShowType.SOLO, "a", "b");

        List<ScoredShow> scored = SimilarShowScorer.topSimilar(
                List.of(festival, solo), ShowType.FESTIVAL, EVENT, Set.of("a", "b"), 3);

        assertThat(scored.getFirst().show().getSetlistId()).isEqualTo("fest");
        assertThat(scored.getFirst().typeMatch()).isTrue();
        assertThat(scored.getFirst().score() - scored.getLast().score())
                .isCloseTo(SimilarShowScorer.TYPE_WEIGHT, offset(1e-9));
        // 겹침: 완전 일치 → Jaccard 1.0
        assertThat(scored.getFirst().overlapScore()).isCloseTo(1.0, offset(1e-9));
        assertThat(scored.getFirst().overlapCount()).isEqualTo(2);
    }

    /** 1년 전 공연의 시기 점수는 정확히 절반(반감기 365일). */
    @Test
    void recencyDecaysWithHalfLife() {
        Show yearAgo = show("old", EVENT.minusDays(365), ShowType.FESTIVAL, "a");

        List<ScoredShow> scored = SimilarShowScorer.topSimilar(
                List.of(yearAgo), ShowType.FESTIVAL, EVENT, Set.of("a"), 1);

        assertThat(scored.getFirst().recencyScore()).isCloseTo(0.5, offset(1e-9));
    }

    @Test
    void jaccardCountsUnionNotJustIntersection() {
        // 예측 {a,b,c,d} vs 셋 {a,b,x,y}: 교집합 2, 합집합 6 → 1/3
        Show partial = show("partial", EVENT.minusDays(10), ShowType.FESTIVAL, "a", "b", "x", "y");

        List<ScoredShow> scored = SimilarShowScorer.topSimilar(
                List.of(partial), ShowType.FESTIVAL, EVENT, Set.of("a", "b", "c", "d"), 1);

        assertThat(scored.getFirst().overlapScore()).isCloseTo(1.0 / 3, offset(1e-9));
        assertThat(scored.getFirst().overlapCount()).isEqualTo(2);
    }

    /** 곡 0건 공연은 비교 대상이 아니다. 예측이 비어도(0곡) 0으로 나누지 않는다. */
    @Test
    void skipsSonglessShowsAndHandlesEmptyPredictions() {
        Show songless = show("empty", EVENT.minusDays(1), ShowType.FESTIVAL);
        Show normal = show("normal", EVENT.minusDays(5), ShowType.FESTIVAL, "a");

        List<ScoredShow> scored = SimilarShowScorer.topSimilar(
                List.of(songless, normal), ShowType.FESTIVAL, EVENT, Set.of(), 3);

        assertThat(scored).hasSize(1);
        assertThat(scored.getFirst().show().getSetlistId()).isEqualTo("normal");
        assertThat(scored.getFirst().overlapScore()).isZero();
    }

    /** 동점이면 최근 공연 우선 — 실행마다 순서가 흔들리면 안 된다. */
    @Test
    void breaksTiesByDateThenId() {
        Show older = show("s-older", EVENT.minusDays(400), ShowType.SOLO, "a");
        Show newer = show("s-newer", EVENT.minusDays(400), ShowType.SOLO, "a");

        List<ScoredShow> scored = SimilarShowScorer.topSimilar(
                List.of(older, newer), ShowType.FESTIVAL, EVENT, Set.of("a"), 2);

        // 날짜 동일 → setlistId 오름차순
        assertThat(scored).extracting(s -> s.show().getSetlistId())
                .containsExactly("s-newer", "s-older");
    }

    @Test
    void limitsResults() {
        List<Show> shows = List.of(
                show("s1", EVENT.minusDays(1), ShowType.FESTIVAL, "a"),
                show("s2", EVENT.minusDays(2), ShowType.FESTIVAL, "a"),
                show("s3", EVENT.minusDays(3), ShowType.FESTIVAL, "a"),
                show("s4", EVENT.minusDays(4), ShowType.FESTIVAL, "a"));

        assertThat(SimilarShowScorer.topSimilar(shows, ShowType.FESTIVAL, EVENT, Set.of("a"), 3))
                .hasSize(3);
    }
}
