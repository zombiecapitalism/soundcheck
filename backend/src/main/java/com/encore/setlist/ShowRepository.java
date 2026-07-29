package com.encore.setlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, String> {

    List<Show> findByArtist_MbidOrderByEventDateDesc(UUID artistMbid);

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
}
