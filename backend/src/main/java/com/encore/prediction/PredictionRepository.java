package com.encore.prediction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    List<Prediction> findByTargetEvent_IdOrderByRankAsc(Long targetEventId);

    boolean existsByTargetEvent_Id(Long targetEventId);

    /**
     * 재계산은 전체 교체다. 파생 deleteBy는 엔티티를 로드해 flush 시점에 지우는데,
     * Hibernate가 INSERT를 DELETE보다 먼저 실행해 (target_event_id, song_key) 유니크에
     * 걸린다. 벌크 JPQL은 즉시 실행되므로 새 예측 INSERT 전에 확실히 비워진다.
     */
    @Modifying
    @Query("delete from Prediction p where p.targetEvent.id = :targetEventId")
    void deleteByTargetEventId(@Param("targetEventId") Long targetEventId);
}
