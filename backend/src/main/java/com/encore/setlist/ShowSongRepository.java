package com.encore.setlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShowSongRepository extends JpaRepository<ShowSong, Long> {

    List<ShowSong> findByShow_SetlistId(String setlistId);

    /** 통계(E5) 대상 곡인지 — tape뿐인 곡은 연주 기록이 없는 것으로 본다. */
    boolean existsByShow_Artist_MbidAndSongKeyAndTapeFalse(java.util.UUID artistMbid, String songKey);
}
