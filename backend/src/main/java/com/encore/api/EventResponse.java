package com.encore.api;

import com.encore.prediction.TargetEvent;
import com.encore.setlist.ShowType;

import java.time.LocalDate;
import java.util.UUID;

/** GET /api/events 항목. */
public record EventResponse(
        Long id,
        String eventName,
        LocalDate eventDate,
        String venueName,
        ShowType expectedShowType,
        boolean verified,
        ArtistSummary artist
) {

    public record ArtistSummary(UUID mbid, String name) {
    }

    /** artist가 fetch join으로 로드된 상태를 전제한다(findAllWithArtist). */
    public static EventResponse from(TargetEvent event) {
        return new EventResponse(
                event.getId(),
                event.getEventName(),
                event.getEventDate(),
                event.getVenueName(),
                event.getExpectedShowType(),
                // 실제 셋리스트가 연결됐으면 적중률을 볼 수 있다 (null 체크만이라 lazy 초기화 없음)
                event.isVerifiable(),
                new ArtistSummary(event.getArtist().getMbid(), event.getArtist().getName()));
    }
}
