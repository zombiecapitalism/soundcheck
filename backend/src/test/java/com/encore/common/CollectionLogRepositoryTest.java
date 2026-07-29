package com.encore.common;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CollectionLogRepositoryTest {

    @Autowired
    private CollectionLogRepository collectionLogRepository;

    @Autowired
    private EntityManager entityManager;

    /** 같은 영속성 컨텍스트에서 꺼내면 방금 만든 객체를 다시 보는 것이라 매핑이 검증되지 않는다. */
    private CollectionLog reload(CollectionLog saved) {
        entityManager.flush();
        entityManager.clear();
        return collectionLogRepository.findById(saved.getId()).orElseThrow();
    }

    @Test
    void recordsSuccessWithCounts() {
        Instant startedAt = Instant.now();
        UUID artistMbid = UUID.randomUUID();
        CollectionCounts counts = CollectionCounts.builder()
                .fetched(40)
                .updated(28)
                .skipped(12)
                .build();

        CollectionLog saved = collectionLogRepository.save(
                CollectionLog.success(JobType.SETLIST_SYNC, artistMbid, counts, startedAt));

        CollectionLog reloaded = reload(saved);
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(reloaded.getJobType()).isEqualTo(JobType.SETLIST_SYNC);
        assertThat(reloaded.getArtistMbid()).isEqualTo(artistMbid);
        assertThat(reloaded.getCounts().getFetched()).isEqualTo(40);
        assertThat(reloaded.getCounts().getUpdated()).isEqualTo(28);
        assertThat(reloaded.getCounts().getSkipped()).isEqualTo(12);
        assertThat(reloaded.getFinishedAt()).isNotNull();
        assertThat(reloaded.getErrorMessage()).isNull();
    }

    /**
     * 카운트가 전부 0이면 @Embedded의 모든 컬럼이 기본값이라, 매핑에 따라 임베디드 자체가
     * null로 돌아올 수 있다. 실패 로그는 카운트를 읽는 쪽에서 NPE가 나면 안 되므로 왕복으로 확인한다.
     */
    @Test
    void recordsFailureWithoutArtistAndCounts() {
        CollectionLog saved = collectionLogRepository.save(
                CollectionLog.failed(JobType.PREDICT, null, "setlist.fm 429 응답", Instant.now()));

        CollectionLog reloaded = reload(saved);
        assertThat(reloaded.getArtistMbid()).isNull();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).isEqualTo("setlist.fm 429 응답");
        assertThat(reloaded.getCounts()).isNotNull();
        assertThat(reloaded.getCounts().getFetched()).isZero();
        assertThat(reloaded.getCounts().getSkipped()).isZero();
    }
}
