package com.encore.setlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, String> {

    /**
     * 곡 목록까지 fetch join으로 한 번에 가져온다 — 예측 집계가 공연마다 곡을 순회하므로
     * 지연 로딩이면 표본 수만큼 추가 쿼리가 나간다. 표본 상한은 호출자가 자른다
     * (컬렉션 fetch join + 페이징은 메모리 페이징으로 떨어지기 때문).
     */
    @Query("""
            select s from Show s
            left join fetch s.songs
            where s.artist.mbid = :artistMbid
            order by s.eventDate desc, s.setlistId desc
            """)
    List<Show> findAllByArtistMbidWithSongs(@Param("artistMbid") UUID artistMbid);

    /** 적중률 매칭용 — 같은 아티스트·공연일의 수집 공연. 같은 날 두 건이면 호출자가 고른다. */
    List<Show> findByArtist_MbidAndEventDate(UUID artistMbid, LocalDate eventDate);

    /**
     * 내한 감지 — 수집 대상 아티스트의 한국(KR) 미래 공연. setlist.fm은 공연이 발표되면
     * 곡 없는 페이지가 먼저 생기므로, 별도 크롤링 없이 수집 데이터만으로 내한이 잡힌다.
     * 곡 0건이어도 감지 대상이다(예측 표본에서 제외되는 것과 무관).
     */
    @Query("""
            select s from Show s
            join fetch s.artist
            where s.countryCode = 'KR' and s.eventDate >= :date
            order by s.eventDate asc, s.setlistId asc
            """)
    List<Show> findUpcomingKoreaShows(@Param("date") LocalDate date);

    long countByArtist_Mbid(UUID artistMbid);

    long countByArtist_MbidAndShowType(UUID artistMbid, ShowType showType);

    // ---- E5 통계용: 곡 0건 공연(등록만 된 미래 공연)을 제외한 카운트.
    // yearlyActivity와 분모를 맞추지 않으면 유형 분포 합계와 연도별 합계가 어긋난다.

    long countByArtist_MbidAndSongCountGreaterThan(UUID artistMbid, short songCount);

    long countByArtist_MbidAndShowTypeAndSongCountGreaterThan(UUID artistMbid, ShowType showType,
                                                              short songCount);

    Optional<Show> findFirstByArtist_MbidOrderByEventDateDesc(UUID artistMbid);

    /** 공연당 평균 곡 수. 등록만 된 빈 셋리스트(곡 0건)는 통계를 왜곡하므로 제외한다. */
    @Query("select avg(s.songCount) from Show s where s.artist.mbid = :artistMbid and s.songCount > 0")
    Double averageSongCount(@Param("artistMbid") UUID artistMbid);

    /** 유형별 평균 곡 수 — 예상 셋리스트(E6)의 곡 수 근거. 없으면 null. */
    @Query("""
            select avg(s.songCount) from Show s
            where s.artist.mbid = :artistMbid and s.songCount > 0 and s.showType = :showType
            """)
    Double averageSongCountByType(@Param("artistMbid") UUID artistMbid, @Param("showType") ShowType showType);

    // ---- 통계(E5) — 예측 표본(최근 20회)과 달리 수집된 전체 공연 대상. tape 곡은 등장으로 안 센다.
    // 네이티브 집계라 별칭을 인터페이스 프로퍼티명과 정확히 맞추려고 따옴표로 감싼다(PG는 소문자화).

    interface SongRateByYear {
        Integer getYear();
        Long getTotalShows();
        Long getPlayedShows();
    }

    interface SongRateByTour {
        String getTourName();
        Long getTotalShows();
        Long getPlayedShows();
    }

    interface SongRateByType {
        String getShowType();
        Long getTotalShows();
        Long getPlayedShows();
    }

    interface YearlyActivity {
        Integer getYear();
        Long getShowCount();
        Double getAvgSongCount();
    }

    @Query(value = """
            select extract(year from s.event_date)::int as "year",
                   count(distinct s.setlist_id) as "totalShows",
                   count(distinct ss.setlist_id) as "playedShows"
            from show s
            left join show_song ss
              on ss.setlist_id = s.setlist_id and ss.song_key = :songKey and ss.is_tape = false
            where s.artist_mbid = :artistMbid and s.song_count > 0
            group by extract(year from s.event_date)
            order by 1
            """, nativeQuery = true)
    List<SongRateByYear> songRateByYear(@Param("artistMbid") UUID artistMbid, @Param("songKey") String songKey);

    /** tour_name은 원본 표기 그대로 group by (표기 흔들림 대응은 화면에서 상위 N개만 노출). */
    @Query(value = """
            select s.tour_name as "tourName",
                   count(distinct s.setlist_id) as "totalShows",
                   count(distinct ss.setlist_id) as "playedShows"
            from show s
            left join show_song ss
              on ss.setlist_id = s.setlist_id and ss.song_key = :songKey and ss.is_tape = false
            where s.artist_mbid = :artistMbid and s.song_count > 0
            group by s.tour_name
            order by count(distinct s.setlist_id) desc, s.tour_name asc nulls last
            """, nativeQuery = true)
    List<SongRateByTour> songRateByTour(@Param("artistMbid") UUID artistMbid, @Param("songKey") String songKey);

    @Query(value = """
            select s.show_type as "showType",
                   count(distinct s.setlist_id) as "totalShows",
                   count(distinct ss.setlist_id) as "playedShows"
            from show s
            left join show_song ss
              on ss.setlist_id = s.setlist_id and ss.song_key = :songKey and ss.is_tape = false
            where s.artist_mbid = :artistMbid and s.song_count > 0
            group by s.show_type
            order by 2 desc
            """, nativeQuery = true)
    List<SongRateByType> songRateByType(@Param("artistMbid") UUID artistMbid, @Param("songKey") String songKey);

    @Query(value = """
            select extract(year from s.event_date)::int as "year",
                   count(*) as "showCount",
                   avg(s.song_count) as "avgSongCount"
            from show s
            where s.artist_mbid = :artistMbid and s.song_count > 0
            group by extract(year from s.event_date)
            order by 1
            """, nativeQuery = true)
    List<YearlyActivity> yearlyActivity(@Param("artistMbid") UUID artistMbid);
}
