package com.encore.prediction;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.setlist.Show;
import com.encore.setlist.ShowRepository;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PredictionRepositoryTest {

    private static final LocalDate BUSAN_DAY_TWO = LocalDate.of(2026, 10, 3);

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private TargetEventRepository targetEventRepository;

    @Autowired
    private PredictionRepository predictionRepository;

    private Artist persistArtist() {
        return artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID())
                .name("Megadeth")
                .build());
    }

    private TargetEvent persistTargetEvent() {
        return targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(persistArtist())
                .eventName("2026 부산국제록페스티벌")
                .eventDate(BUSAN_DAY_TWO)
                .expectedShowType(ShowType.FESTIVAL)
                .build());
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsAreOmitted() {
        TargetEvent saved = persistTargetEvent();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getExpectedSongCount()).isNull();
        assertThat(saved.getActualSetlist()).isNull();
        assertThat(saved.isVerifiable()).isFalse();
    }

    @Test
    void ordersPredictionsByRank() {
        TargetEvent event = persistTargetEvent();

        predictionRepository.saveAndFlush(Prediction.builder()
                .targetEvent(event)
                .songKey("symphony of destruction")
                .songName("Symphony of Destruction")
                .probability(new BigDecimal("0.9000"))
                .rank((short) 2)
                .playedCount((short) 18)
                .sampleSize((short) 20)
                .build());
        predictionRepository.saveAndFlush(Prediction.builder()
                .targetEvent(event)
                .songKey("holy wars")
                .songName("Holy Wars... The Punishment Due")
                .probability(new BigDecimal("0.9500"))
                .rank((short) 1)
                .playedCount((short) 19)
                .sampleSize((short) 20)
                .build());

        List<Prediction> predictions = predictionRepository.findByTargetEvent_IdOrderByRankAsc(event.getId());

        assertThat(predictions).extracting(Prediction::getSongKey)
                .containsExactly("holy wars", "symphony of destruction");
        assertThat(predictions.getFirst().getComputedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateTargetEventForSameArtistAndDate() {
        Artist artist = persistArtist();
        targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist)
                .eventName("부산 2일차")
                .eventDate(BUSAN_DAY_TWO)
                .expectedShowType(ShowType.FESTIVAL)
                .build());

        TargetEvent duplicate = TargetEvent.builder()
                .artist(artist)
                .eventName("부산 2일차 (중복 등록)")
                .eventDate(BUSAN_DAY_TWO)
                .expectedShowType(ShowType.FESTIVAL)
                .build();

        assertThatThrownBy(() -> targetEventRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recordActualSetlistMakesEventVerifiable() {
        TargetEvent event = persistTargetEvent();
        Show actual = showRepository.saveAndFlush(Show.builder()
                .setlistId("actual1")
                .versionId("v1")
                .artist(event.getArtist())
                .eventDate(BUSAN_DAY_TWO)
                .showType(ShowType.FESTIVAL)
                .rawJson("{}")
                .build());

        event.recordActualSetlist(actual);
        targetEventRepository.flush();

        assertThat(event.isVerifiable()).isTrue();
        assertThat(event.getActualSetlist().getSetlistId()).isEqualTo("actual1");
    }
}
