package com.encore.setlist;

import com.encore.artist.Artist;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "show")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Show {

    @Id
    @Column(name = "setlist_id", nullable = false, length = 20)
    private String setlistId;

    /**
     * setlist.fm은 위키 방식이라 같은 setlistId라도 내용이 바뀐다.
     * 낙관적 락 카운터가 아니라 원본이 내려주는 편집 버전 값이다.
     */
    @Column(name = "version_id", nullable = false, length = 20)
    private String versionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artist_mbid", nullable = false)
    private Artist artist;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "tour_name", length = 200)
    private String tourName;

    @Column(name = "venue_name", length = 300)
    private String venueName;

    @Column(name = "city_name", length = 200)
    private String cityName;

    /** DDL이 CHAR(2)이므로 bpchar로 맞춰야 ddl-auto=validate를 통과한다. */
    @Column(name = "country_code", columnDefinition = "bpchar(2)")
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "show_type", nullable = false, length = 20)
    private ShowType showType;

    @Column(name = "song_count", nullable = false)
    private short songCount;

    @Column(name = "source_url")
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", nullable = false, columnDefinition = "jsonb")
    private String rawJson;

    /**
     * 이 행의 내용을 마지막으로 수집한 시각. "마지막 수정 시각"이 아니다.
     * 후처리(classifyAs)로는 움직이면 안 되고 재수집(생성·refreshFrom)에서만 갱신돼야 하므로
     * 타임스탬프 생성 어노테이션 대신 도메인 코드가 직접 관리한다.
     * (@CreationTimestamp는 프로퍼티를 생성 관할로 취급해 refreshFrom의 수동 대입이 UPDATE에 반영되지 않는다.)
     */
    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @OneToMany(mappedBy = "show", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ShowSong> songs = new ArrayList<>();

    @Builder
    private Show(String setlistId, String versionId, Artist artist, LocalDate eventDate, String tourName,
                 String venueName, String cityName, String countryCode, ShowType showType, String sourceUrl,
                 String rawJson) {
        this.setlistId = setlistId;
        this.versionId = versionId;
        this.artist = artist;
        this.eventDate = eventDate;
        this.tourName = tourName;
        this.venueName = venueName;
        this.cityName = cityName;
        this.countryCode = countryCode;
        this.showType = showType != null ? showType : ShowType.UNKNOWN;
        this.sourceUrl = sourceUrl;
        this.rawJson = rawJson;
        this.collectedAt = Instant.now();
    }

    public List<ShowSong> getSongs() {
        return Collections.unmodifiableList(songs);
    }

    /** versionId가 같으면 내용이 그대로이므로 재적재를 건너뛴다. */
    public boolean hasSameVersion(String versionId) {
        return this.versionId.equals(versionId);
    }

    /** 재적재 시 곡 목록을 통째로 교체한다. songCount는 여기서만 갱신되어 목록과 어긋나지 않는다. */
    public void replaceSongs(List<ShowSong> newSongs) {
        this.songs.clear();
        newSongs.forEach(song -> {
            song.assignTo(this);
            this.songs.add(song);
        });
        this.songCount = (short) this.songs.size();
    }

    /** 페스티벌/단독 판정은 수집 후 별도 로직이 내리므로 나중에 반영될 수 있다. */
    public void classifyAs(ShowType showType) {
        this.showType = showType;
    }

    /**
     * versionId가 달라졌을 때 원본이 새로 내려준 내용으로 갱신한다.
     * setlist.fm은 위키라 공연장·투어명·도시처럼 수집 당시 값이 나중에 수정될 수 있으므로
     * 일부만 갱신하면 원본과 어긋난 채 남는다. 곡 목록은 replaceSongs로 따로 교체한다.
     * <p>
     * showType은 복사하지 않는다 — 판정은 별도 단계(classifyAs)의 몫이다. 다만 공연장·투어명이
     * 바뀌면 기존 판정이 낡은 값일 수 있으므로, 호출자는 갱신 후 재판정을 함께 태워야 한다.
     */
    public void refreshFrom(Show source) {
        if (!this.setlistId.equals(source.setlistId)) {
            throw new IllegalArgumentException(
                    "다른 셋리스트의 내용으로 갱신할 수 없습니다: this=%s, source=%s"
                            .formatted(this.setlistId, source.setlistId));
        }
        this.versionId = source.versionId;
        this.eventDate = source.eventDate;
        this.tourName = source.tourName;
        this.venueName = source.venueName;
        this.cityName = source.cityName;
        this.countryCode = source.countryCode;
        this.sourceUrl = source.sourceUrl;
        this.rawJson = source.rawJson;
        this.collectedAt = Instant.now();
    }

    /** tape(입·퇴장 음원)를 뺀 실연주 곡. 예측 집계는 이 목록만 대상으로 한다. */
    public List<ShowSong> playedSongs() {
        return songs.stream().filter(ShowSong::isAggregatable).toList();
    }
}
