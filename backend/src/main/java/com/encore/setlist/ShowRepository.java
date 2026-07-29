package com.encore.setlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, String> {

    /**
     * 곡 목록까지 fetch join으로 한 번에 가져온다 — 예측 집계가 공연마다 곡을 순회하므로
     * 지연 로딩이면 표본 수만큼 추가 쿼리가 나간다. 표본 상한은 호출자가 자른다
     * (컬렉션 fetch join + 페이징은 메모리 페이징으로 떨어지기 때문).
     */
    @Query("""
            select s from Show s
            left join fetch s.songs
            where s.artist.mbid = :artistMbid
            order by s.eventDate desc, s.setlistId desc
            """)
    List<Show> findAllByArtistMbidWithSongs(@Param("artistMbid") UUID artistMbid);

    long countByArtist_Mbid(UUID artistMbid);

    long countByArtist_MbidAndShowType(UUID artistMbid, ShowType showType);

    Optional<Show> findFirstByArtist_MbidOrderByEventDateDesc(UUID artistMbid);

    /** 공연당 평균 곡 수. 등록만 된 빈 셋리스트(곡 0건)는 통계를 왜곡하므로 제외한다. */
    @Query("select avg(s.songCount) from Show s where s.artist.mbid = :artistMbid and s.songCount > 0")
    Double averageSongCount(@Param("artistMbid") UUID artistMbid);
}
