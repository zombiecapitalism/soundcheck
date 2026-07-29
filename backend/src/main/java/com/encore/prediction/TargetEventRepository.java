package com.encore.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TargetEventRepository extends JpaRepository<TargetEvent, Long> {

    /** 아직 열리지 않은(당일 포함) 예측 대상. 지난 공연은 재계산할 이유가 없다. */
    List<TargetEvent> findByEventDateGreaterThanEqual(LocalDate date);
}
