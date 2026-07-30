package com.encore.prediction;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.setlist.ShowType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 변화 요약(E4)용 신규 쿼리 — 벌크 UPDATE 반영과 fetch join 즉시 로딩을 검증한다. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TargetEventRepositoryTest {

    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private TargetEventRepository targetEventRepository;
    @Autowired
    private EntityManager entityManager;

    private TargetEvent event;

    @BeforeEach
    void setUp() {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        event = targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("이벤트").eventDate(LocalDate.of(2026, 10, 2))
                .expectedShowType(ShowType.FESTIVAL).build());
    }

    /** 벌크 UPDATE는 영속성 컨텍스트를 우회한다 — clear 후 재조회로 실제 DB 반영을 확인한다. */
    @Test
    void bulkUpdateWritesAndClearsTrendSummary() {
        targetEventRepository.updateTrendSummary(event.getId(), "요약 본문", Instant.now());
        entityManager.clear();

        TargetEvent updated = targetEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getTrendSummary()).isEqualTo("요약 본문");
        assertThat(updated.getTrendSummaryAt()).isNotNull();

        // 변화가 없어지면 null로 지운다 — 낡은 요약이 남으면 안 된다
        targetEventRepository.updateTrendSummary(event.getId(), null, null);
        entityManager.clear();

        TargetEvent cleared = targetEventRepository.findById(event.getId()).orElseThrow();
        assertThat(cleared.getTrendSummary()).isNull();
        assertThat(cleared.getTrendSummaryAt()).isNull();
    }

    /** Chat·변화 요약은 트랜잭션 밖에서 아티스트 이름을 쓴다 — artist가 즉시 로딩돼 있어야 한다. */
    @Test
    void findByIdWithArtistInitializesArtist() {
        entityManager.clear();

        TargetEvent loaded = targetEventRepository.findByIdWithArtist(event.getId()).orElseThrow();

        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(loaded, "artist")).isTrue();
        assertThat(loaded.getArtist().getName()).isEqualTo("Megadeth");
    }
}
