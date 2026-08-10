package com.encore.setlist;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재분류는 파생값 재계산이어야 한다 — 키워드 추가는 FESTIVAL로 올리고,
 * 키워드 삭제는 UNKNOWN으로 되돌리며, 판정이 같으면 아무것도 건드리지 않는다.
 */
@DataJpaTest
@Import({TestcontainersConfiguration.class, ShowReclassifier.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ShowReclassifierTest {

    @Autowired
    private ShowReclassifier reclassifier;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private FestivalMappingRepository festivalMappingRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private EntityManager entityManager;

    private Show persistShow(String id, String venue, ShowType showType) {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Khruangbin").build());
        Show show = Show.builder()
                .setlistId(id).versionId("v1").artist(artist)
                .eventDate(LocalDate.of(2026, 7, 25))
                .venueName(venue).showType(showType).rawJson("{}")
                .build();
        entityManager.persist(show);
        entityManager.flush();
        return show;
    }

    @Test
    void upgradesToFestivalWhenKeywordAdded() {
        persistShow("rc-1", "GREEN STAGE", ShowType.UNKNOWN);
        persistShow("rc-2", "The Observatory", ShowType.UNKNOWN);
        festivalMappingRepository.saveAndFlush(new FestivalMapping("green stage"));

        int changed = reclassifier.reclassifyAll();
        entityManager.flush();
        entityManager.clear();

        assertThat(changed).isEqualTo(1);
        assertThat(showRepository.findById("rc-1").orElseThrow().getShowType())
                .isEqualTo(ShowType.FESTIVAL);
        assertThat(showRepository.findById("rc-2").orElseThrow().getShowType())
                .isEqualTo(ShowType.UNKNOWN);
    }

    @Test
    void revertsToUnknownWhenKeywordRemoved() {
        persistShow("rc-3", "GREEN STAGE", ShowType.FESTIVAL);

        int changed = reclassifier.reclassifyAll();
        entityManager.flush();
        entityManager.clear();

        assertThat(changed).isEqualTo(1);
        assertThat(showRepository.findById("rc-3").orElseThrow().getShowType())
                .isEqualTo(ShowType.UNKNOWN);
    }

    @Test
    void leavesMatchingClassificationUntouched() {
        // 기본 키워드(festival)에 걸리는 공연 — 매핑 없이도 판정이 이미 맞다
        persistShow("rc-4", "Pentaport Rock Festival", ShowType.FESTIVAL);

        assertThat(reclassifier.reclassifyAll()).isZero();
    }
}
