package com.encore.api;

import com.encore.artist.Artist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/** GET /api/artists/{mbid} — 아티스트 기본 정보 + 수집된 최근 공연 통계. */
public record ArtistDetailResponse(
        UUID mbid,
        String name,
        String sortName,
        String setlistFmUrl,
        RecentShowStats recentShows
) {

    /**
     * 수집 데이터 기반 통계. avgSongCount는 곡 0건(등록만 된 셋리스트)을 제외한 평균이며
     * 수집된 공연이 없으면 null.
     */
    public record RecentShowStats(
            long total,
            long festival,
            LocalDate latestEventDate,
            BigDecimal avgSongCount
    ) {
    }

    public static ArtistDetailResponse from(Artist artist, long total, long festival,
                                            LocalDate latestEventDate, Double avgSongCount) {
        return new ArtistDetailResponse(
                artist.getMbid(),
                artist.getName(),
                artist.getSortName(),
                artist.getSetlistFmUrl(),
                new RecentShowStats(
                        total,
                        festival,
                        latestEventDate,
                        avgSongCount == null
                                ? null
                                : BigDecimal.valueOf(avgSongCount).setScale(1, RoundingMode.HALF_UP)));
    }
}
