package com.encore.prediction;

import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표본 선정 규칙 — 같은 날 여러 세트(실측: A7X 본 세트 + Statica 별칭 세트)는
 * 실연주 곡이 가장 많은 본 세트만 남고, limit은 그 뒤에 적용돼야 한다.
 */
class PredictionSamplingTest {

    private Show show(String setlistId, LocalDate date, int songCount) {
        Show built = Show.builder()
                .setlistId(setlistId).versionId("v1")
                .eventDate(date).showType(ShowType.UNKNOWN).rawJson("{}")
                .build();
        built.replaceSongs(IntStream.rangeClosed(1, songCount)
                .mapToObj(i -> ShowSong.builder()
                        .setIndex((short) 0).positionInSet((short) i).positionTotal((short) i)
                        .songName("Song " + i).songKey("song " + i)
                        .build())
                .toList());
        return built;
    }

    @Test
    void keepsOnlyMainSetWhenSameDayHasAliasSet() {
        // 최근순 입력 — 별칭 세트(4곡)가 본 세트(12곡)보다 앞에 와도 본 세트가 남아야 한다
        Show alias = show("alias-1", LocalDate.of(2026, 8, 8), 4);
        Show main = show("main-1", LocalDate.of(2026, 8, 8), 12);
        Show older = show("solo-1", LocalDate.of(2026, 8, 6), 10);

        List<Show> sample = PredictionSampling.sample(List.of(alias, main, older), 20);

        assertThat(sample).extracting(Show::getSetlistId).containsExactly("main-1", "solo-1");
    }

    @Test
    void samplesMainSetsUpToLimitNotRawEntries() {
        // 날짜 3개 × (본 세트 + 별칭 세트) 6건, limit 3 → 본 세트 3건이어야 한다.
        // 별칭 세트가 슬롯을 차지하면 표본이 최근 1.5일치로 좁아지는 것이 원래 버그였다.
        List<Show> shows = List.of(
                show("a1", LocalDate.of(2026, 8, 8), 4), show("m1", LocalDate.of(2026, 8, 8), 12),
                show("a2", LocalDate.of(2026, 8, 6), 4), show("m2", LocalDate.of(2026, 8, 6), 12),
                show("a3", LocalDate.of(2026, 8, 4), 4), show("m3", LocalDate.of(2026, 8, 4), 12));

        List<Show> sample = PredictionSampling.sample(shows, 3);

        assertThat(sample).extracting(Show::getSetlistId).containsExactly("m1", "m2", "m3");
    }

    @Test
    void breaksTieDeterministicallyBySetlistId() {
        // 곡 수가 같으면 setlistId가 큰 쪽 — 채점(AccuracyService)과 같은 기준이라
        // 근거 표본과 채점 정답이 같은 세트를 가리킨다
        Show first = show("aaa", LocalDate.of(2026, 8, 8), 10);
        Show second = show("bbb", LocalDate.of(2026, 8, 8), 10);

        assertThat(PredictionSampling.sample(List.of(first, second), 20))
                .extracting(Show::getSetlistId).containsExactly("bbb");
        assertThat(PredictionSampling.sample(List.of(second, first), 20))
                .extracting(Show::getSetlistId).containsExactly("bbb");
    }

    @Test
    void excludesShowsWithoutPlayedSongs() {
        Show empty = show("empty-1", LocalDate.of(2026, 8, 8), 0);
        Show played = show("solo-1", LocalDate.of(2026, 8, 6), 10);

        assertThat(PredictionSampling.sample(List.of(empty, played), 20))
                .extracting(Show::getSetlistId).containsExactly("solo-1");
    }
}
