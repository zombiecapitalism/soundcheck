package com.encore.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SongExplanationRepository extends JpaRepository<SongExplanation, Long> {

    Optional<SongExplanation> findByArtistMbidAndSongKey(UUID artistMbid, String songKey);

    /** 새 문서 수집 후 아티스트 단위 무효화 — 벌크 JPQL(로드 없이 즉시 삭제). */
    @Modifying
    @Query("delete from SongExplanation e where e.artistMbid = :artistMbid")
    void deleteByArtistMbid(@Param("artistMbid") UUID artistMbid);
}
