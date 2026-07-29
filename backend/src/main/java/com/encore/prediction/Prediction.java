package com.encore.prediction;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 배치가 사전 계산한 곡별 연주 확률. 재계산 시 갱신이 아니라 교체 대상이므로 상태 변경 메서드를 두지 않는다.
 */
@Entity
@Table(
        name = "prediction",
        uniqueConstraints = @UniqueConstraint(columnNames = {"target_event_id", "song_key"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_event_id", nullable = false)
    private TargetEvent targetEvent;

    @Column(name = "song_key", nullable = false, length = 300)
    private String songKey;

    @Column(name = "song_name", nullable = false, length = 300)
    private String songName;

    @Column(name = "probability", nullable = false, precision = 5, scale = 4)
    private BigDecimal probability;

    @Column(name = "rank", nullable = false)
    private short rank;

    /** 근거 수치: 최근 sampleSize회 중 playedCount회 연주. 화면에 그대로 노출한다. */
    @Column(name = "played_count", nullable = false)
    private short playedCount;

    @Column(name = "sample_size", nullable = false)
    private short sampleSize;

    @Column(name = "avg_position", precision = 4, scale = 1)
    private BigDecimal avgPosition;

    @Column(name = "encore_ratio", precision = 5, scale = 4)
    private BigDecimal encoreRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private String evidence;

    @CreationTimestamp
    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    @Builder
    private Prediction(TargetEvent targetEvent, String songKey, String songName, BigDecimal probability,
                       short rank, short playedCount, short sampleSize, BigDecimal avgPosition,
                       BigDecimal encoreRatio, String evidence) {
        this.targetEvent = targetEvent;
        this.songKey = songKey;
        this.songName = songName;
        this.probability = probability;
        this.rank = rank;
        this.playedCount = playedCount;
        this.sampleSize = sampleSize;
        this.avgPosition = avgPosition;
        this.encoreRatio = encoreRatio;
        this.evidence = evidence;
    }
}
