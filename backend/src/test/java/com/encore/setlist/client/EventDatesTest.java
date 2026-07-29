package com.encore.setlist.client;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventDatesTest {

    @Test
    void parsesDayMonthYear() {
        assertThat(EventDates.parse("02-10-2026")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(EventDates.parse("31-07-2026")).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    /** ISO가 조용히 엉뚱한 날짜로 읽히면 안 된다 — dd 자리에 연도가 들어오면 실패해야 한다. */
    @Test
    void rejectsIsoFormat() {
        assertThatThrownBy(() -> EventDates.parse("2026-10-02"))
                .isInstanceOf(DateTimeParseException.class);
    }

    /** SMART 해석이면 2월 31일이 2월 28일로 조용히 보정된다. STRICT 검증. */
    @Test
    void rejectsImpossibleDate() {
        assertThatThrownBy(() -> EventDates.parse("31-02-2026"))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void rejectsMissingValue() {
        assertThatThrownBy(() -> EventDates.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventDate");
        assertThatThrownBy(() -> EventDates.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
