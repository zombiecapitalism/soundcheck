package com.encore.setlist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShowTypesTest {

    @Test
    void detectsFestivalKeywordInVenueName() {
        assertThat(ShowTypes.classify("Pentaport Rock Festival", null, List.of()))
                .isEqualTo(ShowType.FESTIVAL);
        assertThat(ShowTypes.classify("DOWNLOAD FESTIVAL Grounds", null, List.of()))
                .isEqualTo(ShowType.FESTIVAL);
    }

    @Test
    void detectsFestivalKeywordInTourName() {
        assertThat(ShowTypes.classify("Olympic Park", "Summer Festival Tour 2026", List.of()))
                .isEqualTo(ShowType.FESTIVAL);
    }

    @Test
    void detectsKoreanFestivalKeyword() {
        assertThat(ShowTypes.classify("부산국제록페스티벌", null, List.of()))
                .isEqualTo(ShowType.FESTIVAL);
    }

    /** 키워드가 없는 페스티벌 공연장(삼락생태공원 등)은 수동 매핑으로 잡는다. */
    @Test
    void usesManualMappingKeywords() {
        assertThat(ShowTypes.classify("삼락생태공원", null, List.of("삼락생태공원")))
                .isEqualTo(ShowType.FESTIVAL);
        assertThat(ShowTypes.classify("Songdo Moonlight Park", null, List.of("moonlight park")))
                .isEqualTo(ShowType.FESTIVAL);
    }

    /** 판정 불가는 UNKNOWN — SOLO를 자동으로 단정하지 않는다. */
    @Test
    void returnsUnknownWhenNothingMatches() {
        assertThat(ShowTypes.classify("Tokyo Dome", "World Tour 2026", List.of()))
                .isEqualTo(ShowType.UNKNOWN);
        assertThat(ShowTypes.classify(null, null, List.of()))
                .isEqualTo(ShowType.UNKNOWN);
        assertThat(ShowTypes.classify("Some Arena", null, List.of("  ")))
                .isEqualTo(ShowType.UNKNOWN);
    }
}
