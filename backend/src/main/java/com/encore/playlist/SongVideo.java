package com.encore.playlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 곡별 YouTube 영상 캐시(E12) — videoId null은 "검색했지만 못 찾음"(네거티브 캐시).
 * 재검색으로 API 쿼터를 반복 소모하지 않기 위해 실패도 기록한다.
 */
@Entity
@Table(name = "song_video",
        uniqueConstraints = @UniqueConstraint(columnNames = {"artist_mbid", "song_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SongVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artist_mbid", nullable = false)
    private UUID artistMbid;

    @Column(name = "song_key", nullable = false, length = 300)
    private String songKey;

    @Column(name = "video_id", length = 20)
    private String videoId;

    @Column(name = "video_title", length = 300)
    private String videoTitle;

    @CreationTimestamp
    @Column(name = "searched_at", nullable = false)
    private Instant searchedAt;

    @Builder
    private SongVideo(UUID artistMbid, String songKey, String videoId, String videoTitle) {
        this.artistMbid = artistMbid;
        this.songKey = songKey;
        this.videoId = videoId;
        this.videoTitle = videoTitle;
    }
}
