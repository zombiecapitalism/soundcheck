package com.encore.prediction;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import com.encore.setlist.ShowSong;
import com.encore.setlist.ShowType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccuracyServiceTest {

    private static final LocalDate PAST = LocalDate.of(2026, 7, 1);
    private static final LocalDate FUTURE = LocalDate.of(2026, 12, 1);

    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private TargetEventRepository targetEventRepository;
    @Autowired
    private EntityManager entityManager;

    private AccuracyService service;
    private Artist artist;

    @BeforeEach
    void setUp() {
        service = new AccuracyService(targetEventRepository, showRepository);
        artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
    }

    private TargetEvent persistEvent(LocalDate date) {
        return targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("이벤트 " + date).eventDate(date)
                .expectedShowType(ShowType.FESTIVAL).build());
    }

    private Show persistShow(String id, LocalDate date, String... songNames) {
        Show show = Show.builder()
                .setlistId(id).versionId("v1").artist(artist).eventDate(date)
                .showType(ShowType.FESTIVAL).rawJson("{}")
                .build();
        short pos = 0;
        java.util.List<ShowSong> songs = new java.util.ArrayList<>();
        for (String name : songNames) {
            pos++;
            songs.add(ShowSong.builder()
                    .setIndex((short) 0).positionInSet(pos).positionTotal(pos)
                    .songName(name).songKey(name.toLowerCase())
                    .build());
        }
        show.replaceSongs(songs);
        entityManager.persist(show);
        return show;
    }

    @Test
    void matchesPastEventWithCollectedShow() {
        TargetEvent event = persistEvent(PAST);
        persistShow("match1", PAST, "Holy Wars", "Trust");
        entityManager.flush();

        int matched = service.matchPastEvents();
        entityManager.flush();
        entityManager.clear();

        assertThat(matched).isEqualTo(1);
        TargetEvent reloaded = targetEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.isVerifiable()).isTrue();
        assertThat(reloaded.getActualSetlist().getSetlistId()).isEqualTo("match1");
    }

    /** 등록만 되고 곡이 없는 페이지는 정답이 될 수 없다 — 곡이 채워질 때까지 기다린다. */
    @Test
    void skipsSonglessShowUntilSongsArrive() {
        TargetEvent event = persistEvent(PAST);
        persistShow("empty1", PAST); // 곡 0건
        entityManager.flush();

        assertThat(service.matchPastEvents()).isZero();
        assertThat(targetEventRepository.findById(event.getId()).orElseThrow().isVerifiable()).isFalse();
    }

    @Test
    void ignoresFutureAndAlreadyMatchedEvents() {
        persistEvent(FUTURE);
        TargetEvent past = persistEvent(PAST);
        Show show = persistShow("done1", PAST, "Holy Wars");
        past.recordActualSetlist(show);
        entityManager.flush();

        // 미래 이벤트와 이미 연결된 이벤트 모두 대상이 아니다
        assertThat(service.matchPastEvents()).isZero();
    }

    /** 다른 날짜의 공연은 정답이 아니다. */
    @Test
    void doesNotMatchShowFromDifferentDate() {
        persistEvent(PAST);
        persistShow("other1", PAST.minusDays(1), "Holy Wars");
        entityManager.flush();

        assertThat(service.matchPastEvents()).isZero();
    }
}
