package com.encore.setlist;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 배치 실행 이력. 스키마에 진행 중 상태가 없으므로 작업이 끝난 시점에 결과와 함께 한 번만 기록한다.
 */
@Entity
@Table(name = "collection_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 아티스트 단위가 아닌 작업도 있으므로 연관관계가 아니라 nullable 값으로 둔다. */
    @Column(name = "artist_mbid")
    private UUID artistMbid;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 30)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status;

    @Embedded
    private CollectionCounts counts;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    private CollectionLog(JobType jobType, JobStatus status, UUID artistMbid, CollectionCounts counts,
                          String errorMessage, Instant startedAt) {
        this.jobType = jobType;
        this.status = status;
        this.artistMbid = artistMbid;
        this.counts = counts;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = Instant.now();
    }

    public static CollectionLog success(JobType jobType, UUID artistMbid, CollectionCounts counts, Instant startedAt) {
        return new CollectionLog(jobType, JobStatus.SUCCESS, artistMbid, counts, null, startedAt);
    }

    /** 일부만 처리된 경우. 어디까지 됐는지 알아야 재시도 범위를 잡을 수 있어 counts와 사유를 함께 남긴다. */
    public static CollectionLog partial(JobType jobType, UUID artistMbid, CollectionCounts counts,
                                        String errorMessage, Instant startedAt) {
        return new CollectionLog(jobType, JobStatus.PARTIAL, artistMbid, counts, errorMessage, startedAt);
    }

    public static CollectionLog failed(JobType jobType, UUID artistMbid, String errorMessage, Instant startedAt) {
        return new CollectionLog(jobType, JobStatus.FAILED, artistMbid, CollectionCounts.none(), errorMessage, startedAt);
    }
}
