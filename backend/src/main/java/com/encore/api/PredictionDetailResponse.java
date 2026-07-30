package com.encore.api;

import com.encore.prediction.Prediction;
import com.encore.setlist.Show;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/events/{id}/predictions/{songKey} — 곡 하나의 예측 근거를 타임라인으로 푼다.
 * history는 예측과 같은 표본 선정 규칙의 최근 공연 목록(최근순)이며, 연주/미연주를
 * 모두 담아 "최근 20회 중 19회"의 공백까지 보이게 한다.
 */
public record PredictionDetailResponse(
        PredictionResponse prediction,
        List<HistoryEntry> history
) {

    public record HistoryEntry(
            String setlistId,
            LocalDate eventDate,
            String venueName,
            String cityName,
            ShowType showType,
            int playedSongCount,
            boolean played,
            Integer position,
            Boolean encore
    ) {

        /** show의 곡 목록은 fetch join으로 로드된 상태를 전제한다. */
        static HistoryEntry from(Show show, String songKey) {
            ShowSong song = show.playedSongs().stream()
                    .filter(s -> s.getSongKey().equals(songKey))
                    .findFirst()
                    .orElse(null);
            return new HistoryEntry(
                    show.getSetlistId(),
                    show.getEventDate(),
                    show.getVenueName(),
                    show.getCityName(),
                    show.getShowType(),
                    show.playedSongs().size(),
                    song != null,
                    song != null ? (int) song.getPositionTotal() : null,
                    song != null ? song.isEncore() : null);
        }
    }

    public static PredictionDetailResponse from(Prediction prediction, List<Show> sampleShows) {
        return new PredictionDetailResponse(
                PredictionResponse.from(prediction),
                sampleShows.stream()
                        .map(show -> HistoryEntry.from(show, prediction.getSongKey()))
                        .toList());
    }
}
