package com.encore.api;

import com.encore.setlist.ShowRepository.SongRateByTour;
import com.encore.setlist.ShowRepository.SongRateByType;
import com.encore.setlist.ShowRepository.SongRateByYear;

import java.util.List;

/**
 * GET /api/artists/{mbid}/songs/{songKey}/stats — 곡의 장기 통계(E5).
 * 예측 표본(최근 20회)과 달리 수집된 전체 공연이 대상이다. 분모는 곡 있는 공연 수.
 */
public record SongStatsResponse(
        List<YearlyRate> yearly,
        List<TourRate> tours,
        List<TypeRate> types
) {

    public record YearlyRate(int year, long totalShows, long playedShows) {
    }

    /** tourName은 setlist.fm 원본 표기 그대로 — null이면 투어 없는 공연 묶음. */
    public record TourRate(String tourName, long totalShows, long playedShows) {
    }

    public record TypeRate(String showType, long totalShows, long playedShows) {
    }

    static SongStatsResponse from(List<SongRateByYear> yearly, List<SongRateByTour> tours,
                                  List<SongRateByType> types) {
        return new SongStatsResponse(
                yearly.stream()
                        .map(row -> new YearlyRate(row.getYear(), row.getTotalShows(), row.getPlayedShows()))
                        .toList(),
                tours.stream()
                        .map(row -> new TourRate(row.getTourName(), row.getTotalShows(), row.getPlayedShows()))
                        .toList(),
                types.stream()
                        .map(row -> new TypeRate(row.getShowType(), row.getTotalShows(), row.getPlayedShows()))
                        .toList());
    }
}
