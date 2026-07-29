package com.encore.prediction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    List<Prediction> findByTargetEvent_IdOrderByRankAsc(Long targetEventId);
}
