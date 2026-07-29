package com.encore.prediction;

import com.encore.artist.Artist;
import com.encore.setlist.Show;
import com.encore.setlist.ShowType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(
        name = "target_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"artist_mbid", "event_date"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TargetEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artist_mbid", nullable = false)
    private Artist artist;

    @Column(name = "event_name", nullable = false, length = 200)
    private String eventName;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "venue_name", length = 300)
    private String venueName;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_show_type", nullable = false, length = 20)
    private ShowType expectedShowType;

    /** 페스티벌 셋은 단독 공연보다 짧아 예상 곡 수를 따로 잡는다. */
    @Column(name = "expected_song_count")
    private Short expectedSongCount;

    /** 공연 전에는 비어 있고, 공연 후 적중률 검증 시점에 채워진다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actual_setlist_id")
    private Show actualSetlist;

    @Builder
    private TargetEvent(Artist artist, String eventName, LocalDate eventDate, String venueName,
                        ShowType expectedShowType, Short expectedSongCount) {
        this.artist = artist;
        this.eventName = eventName;
        this.eventDate = eventDate;
        this.venueName = venueName;
        this.expectedShowType = expectedShowType;
        this.expectedSongCount = expectedSongCount;
    }

    /** 공연이 끝난 뒤 실제 셋리스트를 연결해 예측 적중률 검증의 정답으로 삼는다. */
    public void recordActualSetlist(Show actualSetlist) {
        this.actualSetlist = actualSetlist;
    }

    public boolean isVerifiable() {
        return actualSetlist != null;
    }
}
