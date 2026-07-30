package com.encore.prediction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    List<Prediction> findByTargetEvent_IdOrderByRankAsc(Long targetEventId);

    boolean existsByTargetEvent_Id(Long targetEventId);

    Optional<Prediction> findByTargetEvent_IdAndSongKey(Long targetEventId, String songKey);

    /** 곡 설명 요청 검증용 — 예측에 등장한 곡인지. 임의 songKey로 임베딩·LLM 비용이 새는 것을 막는다. */
    boolean existsByTargetEvent_Artist_MbidAndSongKey(UUID artistMbid, String songKey);

    /**
     * 곡 설명(E-RAG)의 원본 곡명 조회 — 곡명을 클라이언트 파라미터로 받으면 임의 텍스트가
     * LLM 프롬프트에 들어가고 그 결과가 공용 캐시에 고정된다(프롬프트 주입·캐시 오염).
     * 서버가 저장된 song_name을 쓰는 것이 유일하게 안전한 경로다.
     */
    Optional<Prediction> findFirstByTargetEvent_Artist_MbidAndSongKey(UUID artistMbid, String songKey);

    /**
     * 재계산은 전체 교체다. 파생 deleteBy는 엔티티를 로드해 flush 시점에 지우는데,
     * Hibernate가 INSERT를 DELETE보다 먼저 실행해 (target_event_id, song_key) 유니크에
     * 걸린다. 벌크 JPQL은 즉시 실행되므로 새 예측 INSERT 전에 확실히 비워진다.
     */
    @Modifying
    @Query("delete from Prediction p where p.targetEvent.id = :targetEventId")
    void deleteByTargetEventId(@Param("targetEventId") Long targetEventId);
}
