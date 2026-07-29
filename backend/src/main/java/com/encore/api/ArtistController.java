package com.encore.api;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import com.encore.setlist.ShowType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/artists")
public class ArtistController {

    private final ArtistRepository artistRepository;
    private final ShowRepository showRepository;

    public ArtistController(ArtistRepository artistRepository, ShowRepository showRepository) {
        this.artistRepository = artistRepository;
        this.showRepository = showRepository;
    }

    /** 아티스트 기본 정보 + 수집된 최근 공연 통계(집계 쿼리 — 예측 계산과 무관). */
    @GetMapping("/{mbid}")
    public ArtistDetailResponse artist(@PathVariable UUID mbid) {
        Artist artist = artistRepository.findById(mbid)
                .orElseThrow(() -> new ApiNotFoundException("존재하지 않는 아티스트입니다: " + mbid));

        LocalDate latestEventDate = showRepository.findFirstByArtist_MbidOrderByEventDateDesc(mbid)
                .map(Show::getEventDate)
                .orElse(null);
        return ArtistDetailResponse.from(
                artist,
                showRepository.countByArtist_Mbid(mbid),
                showRepository.countByArtist_MbidAndShowType(mbid, ShowType.FESTIVAL),
                latestEventDate,
                showRepository.averageSongCount(mbid));
    }
}
