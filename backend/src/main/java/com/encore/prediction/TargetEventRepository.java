package com.encore.prediction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TargetEventRepository extends JpaRepository<TargetEvent, Long> {

    /** 아직 열리지 않은(당일 포함) 예측 대상. 지난 공연은 재계산할 이유가 없다. */
    List<TargetEvent> findByEventDateGreaterThanEqual(LocalDate date);

    /** 목록 응답에 아티스트 이름이 필요하므로 fetch join — open-in-view가 꺼져 있어 지연 로딩이 안 된다. */
    @Query("select e from TargetEvent e join fetch e.artist order by e.eventDate asc, e.id asc")
    List<TargetEvent> findAllWithArtist();

    /**
     * 아티스트 이름까지 필요한 단건 조회(Chat 프롬프트·변화 요약) — open-in-view가 꺼져 있어
     * 트랜잭션 밖에서 getArtist().getName()을 부르면 LazyInitializationException이 난다.
     */
    @Query("select e from TargetEvent e join fetch e.artist where e.id = :id")
    Optional<TargetEvent> findByIdWithArtist(@Param("id") Long id);

    /**
     * 변화 요약 저장(E4) — 벌크 UPDATE라 엔티티 로드 없이 짧은 트랜잭션으로 끝난다.
     * LLM 호출을 트랜잭션 밖에 두기 위한 분리(TrendSummaryService 참고).
     */
    @Modifying
    @Transactional
    @Query("""
            update TargetEvent e
            set e.trendSummary = :summary, e.trendSummaryAt = :at
            where e.id = :id
            """)
    void updateTrendSummary(@Param("id") Long id, @Param("summary") String summary,
                            @Param("at") Instant at);

    /** 공연이 끝났는데 아직 실제 셋리스트가 연결되지 않은 이벤트 — 적중률 매칭 대상. */
    List<TargetEvent> findByEventDateBeforeAndActualSetlistIsNull(LocalDate date);

    /** 내한 감지 항목이 이미 이벤트로 등록됐는지 — 유니크 키(artist, date)와 같은 기준. */
    boolean existsByArtist_MbidAndEventDate(UUID artistMbid, LocalDate eventDate);

    /**
     * 적중률 조회용 — 실제 셋리스트와 그 곡 목록까지 한 번에 로드한다.
     * inner join이라 미검증(actualSetlist null) 이벤트는 빈 결과다. 웹 계층이 지연 로딩
     * 트랜잭션 없이 쓸 수 있다(open-in-view 꺼짐).
     */
    @Query("""
            select e from TargetEvent e
            join fetch e.actualSetlist actual
            left join fetch actual.songs
            where e.id = :id
            """)
    Optional<TargetEvent> findVerifiedWithActualSongs(@Param("id") Long id);

    /** 적중률 아카이브 — 검증된(실제 셋리스트 연결) 이벤트 전체, 최근 공연부터. */
    @Query("""
            select e from TargetEvent e
            join fetch e.artist
            join fetch e.actualSetlist actual
            left join fetch actual.songs
            order by e.eventDate desc, e.id desc
            """)
    List<TargetEvent> findAllVerifiedWithActualSongs();
}
