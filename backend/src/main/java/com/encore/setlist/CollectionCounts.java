package com.encore.setlist;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 같은 타입의 카운터 세 개가 나란히 놓이면 인자 순서가 바뀌어도 컴파일러가 잡지 못하므로 묶어서 이름으로 넘긴다. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionCounts {

    @Column(name = "fetched_count", nullable = false)
    private int fetched;

    @Column(name = "updated_count", nullable = false)
    private int updated;

    /** versionId가 같아 재적재하지 않고 넘어간 건수. */
    @Column(name = "skipped_count", nullable = false)
    private int skipped;

    @Builder
    private CollectionCounts(int fetched, int updated, int skipped) {
        this.fetched = fetched;
        this.updated = updated;
        this.skipped = skipped;
    }

    public static CollectionCounts none() {
        return CollectionCounts.builder().build();
    }
}
