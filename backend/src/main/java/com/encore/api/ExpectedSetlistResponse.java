package com.encore.api;

import com.encore.prediction.SetlistComposer.Composed;
import com.encore.prediction.SetlistComposer.Entry;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/events/{id}/expected-setlist — 본편/앙코르 블록 구조의 예상 셋리스트(E6).
 * expectedSongCount는 곡 수 근거(유형별 평균)를 그대로 노출해 "왜 이 규모인가"를 설명한다.
 */
public record ExpectedSetlistResponse(
        int expectedSongCount,
        List<Item> main,
        List<Item> encore
) {

    public record Item(int order, String songKey, String songName, BigDecimal probability) {
    }

    static ExpectedSetlistResponse from(Composed composed, int expectedSongCount) {
        List<Item> main = toItems(composed.main(), 1);
        // 앙코르 순번은 본편에 이어 계속 — 화면의 "n번째 곡" 감각과 일치
        List<Item> encore = toItems(composed.encore(), main.size() + 1);
        return new ExpectedSetlistResponse(expectedSongCount, main, encore);
    }

    private static List<Item> toItems(List<Entry> entries, int startOrder) {
        return java.util.stream.IntStream.range(0, entries.size())
                .mapToObj(i -> new Item(startOrder + i, entries.get(i).songKey(),
                        entries.get(i).songName(), entries.get(i).probability()))
                .toList();
    }
}
