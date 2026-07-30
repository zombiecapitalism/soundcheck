package com.encore.api;

import com.encore.prediction.SimilarShowScorer.ScoredShow;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/events/{id}/similar-shows — 예측 대상과 가장 비슷한 과거 공연 상위 N(E11).
 * 셋리스트까지 함께 실어 "이런 공연이 될 것"의 실물 예시를 보여준다.
 */
public record SimilarShowsResponse(List<SimilarShow> shows) {

    public record SetlistEntry(int position, String songName, boolean encore) {
    }

    public record SimilarShow(
            String setlistId,
            LocalDate eventDate,
            String venueName,
            String cityName,
            ShowType showType,
            BigDecimal score,
            boolean typeMatch,
            /** 예측 상위 K곡 중 이 공연에서 실제 연주된 곡 수 */
            int overlapCount,
            List<SetlistEntry> setlist
    ) {

        static SimilarShow from(ScoredShow scored) {
            List<SetlistEntry> setlist = scored.show().playedSongs().stream()
                    .map(song -> new SetlistEntry(song.getPositionTotal(), song.getSongName(),
                            song.isEncore()))
                    .toList();
            return new SimilarShow(
                    scored.show().getSetlistId(),
                    scored.show().getEventDate(),
                    scored.show().getVenueName(),
                    scored.show().getCityName(),
                    scored.show().getShowType(),
                    BigDecimal.valueOf(scored.score()).setScale(4, RoundingMode.HALF_UP),
                    scored.typeMatch(),
                    scored.overlapCount(),
                    setlist);
        }
    }

    public static SimilarShowsResponse from(List<ScoredShow> scored) {
        return new SimilarShowsResponse(scored.stream().map(SimilarShow::from).toList());
    }
}
