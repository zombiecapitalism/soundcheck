package com.encore.batch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionLogRepository extends JpaRepository<CollectionLog, Long> {

    /** 관리자 대시보드용 최근 이력. id 역순 = 기록 역순. */
    List<CollectionLog> findTop30ByOrderByIdDesc();

    /** retention(LogRetention) — 90일 지난 이력 정리. */
    long deleteByStartedAtBefore(java.time.Instant cutoff);
}
