package com.encore.artist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "artist")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Artist {

    /** setlist.fm이 아닌 MusicBrainz가 부여한 식별자. 밴드 이름은 키로 쓰지 않는다. */
    @Id
    @Column(name = "mbid", nullable = false)
    private UUID mbid;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "sort_name", length = 200)
    private String sortName;

    @Column(name = "setlist_fm_url")
    private String setlistFmUrl;

    @Column(name = "is_target", nullable = false)
    private boolean target;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    private Artist(UUID mbid, String name, String sortName, String setlistFmUrl, boolean target) {
        this.mbid = mbid;
        this.name = name;
        this.sortName = sortName;
        this.setlistFmUrl = setlistFmUrl;
        this.target = target;
    }

    /** setlist.fm 재조회 시 변할 수 있는 표기 정보만 갱신한다. mbid는 식별자이므로 바뀌지 않는다. */
    public void updateProfile(String name, String sortName, String setlistFmUrl) {
        this.name = name;
        this.sortName = sortName;
        this.setlistFmUrl = setlistFmUrl;
    }

    public void markAsCollectionTarget() {
        this.target = true;
    }

    public void releaseFromCollectionTarget() {
        this.target = false;
    }
}
