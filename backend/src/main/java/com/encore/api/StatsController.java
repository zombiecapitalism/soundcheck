package com.encore.api;

import com.encore.artist.ArtistRepository;
import com.encore.setlist.ShowRepository;
import com.encore.setlist.ShowSongRepository;
import com.encore.setlist.ShowType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 셋리스트 통계(E5) — 예측(최근 표본)과 구분되는, 수집된 전체 공연 대상 집계.
 * 조회 시 집계 쿼리를 실행한다. 전체 공연 수가 아티스트당 수백 건 규모라 사전 계산은 과하다.
 */
@RestController
@RequestMapping("/api/artists")
public class StatsController {

    private final ArtistRepository artistRepository;
    private final ShowRepository showRepository;
    private final ShowSongRepository showSongRepository;

    public StatsController(ArtistRepository artistRepository, ShowRepository showRepository,
                           ShowSongRepository showSongRepository) {
        this.artistRepository = artistRepository;
        this.showRepository = showRepository;
        this.showSongRepository = showSongRepository;
    }

    @GetMapping("/{mbid}/stats")
    public ArtistStatsResponse artistStats(@PathVariable UUID mbid) {
        requireArtist(mbid);
        // 곡 0건 공연은 제외 — yearlyActivity와 같은 분모라야 유형 분포와 연도별 합계가 일치한다
        long festival = showRepository
                .countByArtist_MbidAndShowTypeAndSongCountGreaterThan(mbid, ShowType.FESTIVAL, (short) 0);
        long solo = showRepository
                .countByArtist_MbidAndShowTypeAndSongCountGreaterThan(mbid, ShowType.SOLO, (short) 0);
        long unknown = showRepository.countByArtist_MbidAndSongCountGreaterThan(mbid, (short) 0)
                - festival - solo;
        return ArtistStatsResponse.from(showRepository.yearlyActivity(mbid), festival, solo, unknown);
    }

    /** songKey는 URL 인코딩된 정규화 키 — 예측 상세와 같은 규약. */
    @GetMapping("/{mbid}/songs/{songKey}/stats")
    public SongStatsResponse songStats(@PathVariable UUID mbid, @PathVariable String songKey) {
        requireArtist(mbid);
        if (!showSongRepository.existsByShow_Artist_MbidAndSongKeyAndTapeFalse(mbid, songKey)) {
            throw new ApiNotFoundException("연주 기록이 없는 곡입니다: " + songKey);
        }
        return SongStatsResponse.from(
                showRepository.songRateByYear(mbid, songKey),
                showRepository.songRateByTour(mbid, songKey),
                showRepository.songRateByType(mbid, songKey));
    }

    private void requireArtist(UUID mbid) {
        if (!artistRepository.existsById(mbid)) {
            throw new ApiNotFoundException("존재하지 않는 아티스트입니다: " + mbid);
        }
    }
}
