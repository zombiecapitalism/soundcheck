package com.encore.setlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(
        name = "show_song",
        uniqueConstraints = @UniqueConstraint(name = "uq_show_song", columnNames = {"setlist_id", "position_total"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShowSong {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "setlist_id", nullable = false)
    private Show show;

    /** 0=본편, 1..n=앙코르 순번. */
    @Column(name = "set_index", nullable = false)
    private short setIndex;

    @Column(name = "is_encore", nullable = false)
    private boolean encore;

    @Column(name = "position_in_set", nullable = false)
    private short positionInSet;

    @Column(name = "position_total", nullable = false)
    private short positionTotal;

    /** 원본 표기. 정규화는 손실 변환이므로 항상 함께 보관한다. */
    @Column(name = "song_name", nullable = false, length = 300)
    private String songName;

    /** 정규화된 곡명. 집계는 이 값을 기준으로 한다. */
    @Column(name = "song_key", nullable = false, length = 300)
    private String songKey;

    @Column(name = "is_cover", nullable = false)
    private boolean cover;

    @Column(name = "cover_artist", length = 200)
    private String coverArtist;

    /** true면 실제 연주가 아닌 입·퇴장 음원이다. */
    @Column(name = "is_tape", nullable = false)
    private boolean tape;

    @Column(name = "note")
    private String note;

    /** show는 빌더로 받지 않는다 — 부모 연결은 Show.replaceSongs(→ assignTo)가 유일한 경로다. */
    @Builder
    private ShowSong(short setIndex, boolean encore, short positionInSet, short positionTotal,
                     String songName, String songKey, boolean cover, String coverArtist, boolean tape, String note) {
        this.setIndex = setIndex;
        this.encore = encore;
        this.positionInSet = positionInSet;
        this.positionTotal = positionTotal;
        this.songName = songName;
        this.songKey = songKey;
        this.cover = cover;
        this.coverArtist = coverArtist;
        this.tape = tape;
        this.note = note;
    }

    /** 커버곡은 실제로 연주하므로 집계에 포함하고, tape만 제외한다. */
    public boolean isAggregatable() {
        return !tape;
    }

    void assignTo(Show show) {
        this.show = show;
    }
}
