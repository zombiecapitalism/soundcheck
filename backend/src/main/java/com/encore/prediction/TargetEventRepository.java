package com.encore.prediction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface TargetEventRepository extends JpaRepository<TargetEvent, Long> {

    /** 아직 열리지 않은(당일 포함) 예측 대상. 지난 공연은 재계산할 이유가 없다. */
    List<TargetEvent> findByEventDateGreaterThanEqual(LocalDate date);

    /** 목록 응답에 아티스트 이름이 필요하므로 fetch join — open-in-view가 꺼져 있어 지연 로딩이 안 된다. */
    @Query("select e from TargetEvent e join fetch e.artist order by e.eventDate asc, e.id asc")
    List<TargetEvent> findAllWithArtist();
}
