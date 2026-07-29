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
import org.hibernate.annotations.CreationTimestamp;
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

    @CreationTimestamp
    @Column(name = "collected_at", nullable = false, updatable = false)
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

    public void refreshFrom(String versionId, String rawJson) {
        this.versionId = versionId;
        this.rawJson = rawJson;
    }

    /** tape(입·퇴장 음원)를 뺀 실연주 곡. 예측 집계는 이 목록만 대상으로 한다. */
    public List<ShowSong> playedSongs() {
        return songs.stream().filter(ShowSong::isAggregatable).toList();
    }
}
