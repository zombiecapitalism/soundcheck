package com.encore.setlist.client;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/**
 * setlist.fm의 eventDate는 ISO가 아니라 "dd-MM-yyyy" 문자열이다(CLAUDE.md).
 * 파싱해서 DATE로 저장하고 문자열 정렬을 하지 않는다.
 */
public final class EventDates {

    /**
     * STRICT가 아니면 "31-02-2026" 같은 불가능한 날짜가 조용히 보정되고,
     * ISO 형식("2026-02-31")이 들어와도 자리수가 우연히 맞으면 엉뚱한 날짜로 읽힌다.
     * 둘 다 소리 내며 실패해야 수집 데이터 오염을 막는다.
     */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT);

    private EventDates() {
    }

    public static LocalDate parse(String eventDate) {
        if (eventDate == null || eventDate.isBlank()) {
            throw new IllegalArgumentException("eventDate가 비어 있습니다");
        }
        return LocalDate.parse(eventDate, FORMAT);
    }
}
