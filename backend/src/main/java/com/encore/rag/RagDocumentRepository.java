package com.encore.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    /** 같은 아티스트·같은 출처 URL은 한 번만 수집한다 — 재수집 스킵 기준. */
    boolean existsByArtistMbidAndSourceUrl(UUID artistMbid, String sourceUrl);

    /** 문서가 하나도 없으면 임베딩·검색을 건너뛰고 바로 "정보 없음"을 낼 수 있다. */
    boolean existsByArtistMbid(UUID artistMbid);
}
