package com.encore.api;

import com.encore.setlist.ShowRepository.YearlyActivity;

import java.util.List;

/** GET /api/artists/{mbid}/stats — 아티스트 활동 요약(E5). 수집된 전체 공연 대상. */
public record ArtistStatsResponse(
        List<YearlyStat> yearly,
        TypeDistribution typeDistribution
) {

    public record YearlyStat(int year, long showCount, Double avgSongCount) {
    }

    public record TypeDistribution(long festival, long solo, long unknown) {
    }

    static ArtistStatsResponse from(List<YearlyActivity> yearly, long festival, long solo, long unknown) {
        return new ArtistStatsResponse(
                yearly.stream()
                        .map(row -> new YearlyStat(row.getYear(), row.getShowCount(), row.getAvgSongCount()))
                        .toList(),
                new TypeDistribution(festival, solo, unknown));
    }
}
