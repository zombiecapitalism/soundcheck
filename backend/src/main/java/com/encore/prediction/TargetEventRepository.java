package com.encore.prediction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TargetEventRepository extends JpaRepository<TargetEvent, Long> {

    /** 아직 열리지 않은(당일 포함) 예측 대상. 지난 공연은 재계산할 이유가 없다. */
    List<TargetEvent> findByEventDateGreaterThanEqual(LocalDate date);

    /** 목록 응답에 아티스트 이름이 필요하므로 fetch join — open-in-view가 꺼져 있어 지연 로딩이 안 된다. */
    @Query("select e from TargetEvent e join fetch e.artist order by e.eventDate asc, e.id asc")
    List<TargetEvent> findAllWithArtist();

    /** 공연이 끝났는데 아직 실제 셋리스트가 연결되지 않은 이벤트 — 적중률 매칭 대상. */
    List<TargetEvent> findByEventDateBeforeAndActualSetlistIsNull(LocalDate date);

    /** 내한 감지 항목이 이미 이벤트로 등록됐는지 — 유니크 키(artist, date)와 같은 기준. */
    boolean existsByArtist_MbidAndEventDate(UUID artistMbid, LocalDate eventDate);
}
