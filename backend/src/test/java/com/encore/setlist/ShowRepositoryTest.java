package com.encore.setlist;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ShowRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private ShowSongRepository showSongRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private EntityManager entityManager;

    private Artist persistArtist() {
        return artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID())
                .name("Megadeth")
                .build());
    }

    private Show.ShowBuilder showBuilder(String setlistId) {
        return Show.builder()
                .setlistId(setlistId)
                .versionId("v1")
                .artist(persistArtist())
                .eventDate(LocalDate.of(2026, 8, 1))
                .rawJson("{}");
    }

    private ShowSong.ShowSongBuilder songBuilder(short position, String songName, String songKey) {
        return ShowSong.builder()
                .setIndex((short) 0)
                .positionInSet(position)
                .positionTotal(position)
                .songName(songName)
                .songKey(songKey);
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsAreOmitted() {
        Show saved = showRepository.saveAndFlush(showBuilder("default1").build());

        assertThat(saved.getShowType()).isEqualTo(ShowType.UNKNOWN);
        assertThat(saved.getSongCount()).isZero();
        assertThat(saved.getCollectedAt()).isNotNull();
        assertThat(saved.getSongs()).isEmpty();
    }

    @Test
    void storesRawJsonWithoutDoubleEncoding() {
        String json = "{\"id\":\"abc123\",\"tour\":{\"name\":\"World Tour\"}}";

        showRepository.saveAndFlush(showBuilder("abc123")
                .showType(ShowType.FESTIVAL)
                .rawJson(json)
                .build());
        entityManager.clear();

        // jsonb는 저장 시 콜론/콤마 뒤 공백을 정규화하므로 원본과 바이트 단위로는 다를 수 있다.
        // 이중 인코딩(문자열이 통째로 한 번 더 따옴표에 감싸지는 현상) 여부는 JSON 트리로 비교해야 드러난다.
        String storedAsText = (String) entityManager
                .createNativeQuery("select raw_json::text from show where setlist_id = :id")
                .setParameter("id", "abc123")
                .getSingleResult();
        assertThat(MAPPER.readTree(storedAsText)).isEqualTo(MAPPER.readTree(json));

        Show reloaded = showRepository.findById("abc123").orElseThrow();
        assertThat(MAPPER.readTree(reloaded.getRawJson())).isEqualTo(MAPPER.readTree(json));
        assertThat(reloaded.getShowType()).isEqualTo(ShowType.FESTIVAL);
    }

    /**
     * uq_show_song은 재적재를 위해 커밋 시점으로 검사가 미뤄져 있다(V2 마이그레이션).
     * 제약 자체는 그대로 살아 있으므로, 검사를 즉시로 되돌리면 중복은 여전히 거부된다.
     */
    @Test
    void rejectsDuplicatePositionTotalWithinSameShow() {
        Show show = showRepository.saveAndFlush(showBuilder("dup1").build());
        entityManager.createNativeQuery("set constraints uq_show_song immediate").executeUpdate();

        showSongRepository.saveAndFlush(
                songBuilder((short) 1, "Symphony of Destruction", "symphony of destruction").show(show).build());

        ShowSong duplicate = songBuilder((short) 1, "Holy Wars", "holy wars").show(show).build();

        assertThatThrownBy(() -> showSongRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 재적재는 같은 position_total을 다시 채우므로, 새 곡 INSERT 전에 기존 행 DELETE가 먼저 나가야 한다.
     * 영속 상태 엔티티를 더티 체킹으로 flush할 때만 orphanRemoval이 그 순서를 보장한다
     * (save()는 ID가 이미 있는 엔티티에 merge()를 태워 삭제가 누락된다).
     */
    @Test
    void replaceSongsKeepsSongCountInSyncAndRemovesOldRows() {
        Show show = showRepository.save(showBuilder("replace1").build());

        show.replaceSongs(List.of(
                songBuilder((short) 1, "Hangar 18", "hangar 18").build(),
                songBuilder((short) 2, "Holy Wars", "holy wars").build()));
        entityManager.flush();

        assertThat(show.getSongCount()).isEqualTo((short) 2);
        assertThat(showSongRepository.findByShow_SetlistId("replace1")).hasSize(2);

        show.replaceSongs(List.of(songBuilder((short) 1, "Peace Sells", "peace sells").build()));
        entityManager.flush();
        entityManager.clear();

        assertThat(showRepository.findById("replace1").orElseThrow().getSongCount()).isEqualTo((short) 1);
        assertThat(showSongRepository.findByShow_SetlistId("replace1"))
                .extracting(ShowSong::getSongKey)
                .containsExactly("peace sells");
    }

    @Test
    void playedSongsExcludeTape() {
        Show show = showRepository.save(showBuilder("tape1").build());

        show.replaceSongs(List.of(
                songBuilder((short) 1, "Prince of Darkness", "prince of darkness").tape(true).build(),
                songBuilder((short) 2, "Hangar 18", "hangar 18").build(),
                songBuilder((short) 3, "Holy Wars", "holy wars").build()));
        entityManager.flush();

        assertThat(show.getSongCount()).isEqualTo((short) 3);
        assertThat(show.playedSongs())
                .extracting(ShowSong::getSongKey)
                .containsExactly("hangar 18", "holy wars");
    }

    @Test
    void detectsVersionChange() {
        Show show = showRepository.save(showBuilder("ver1").build());

        assertThat(show.hasSameVersion("v1")).isTrue();
        assertThat(show.hasSameVersion("v2")).isFalse();

        show.refreshFrom("v2", "{\"updated\":true}");
        entityManager.flush();

        assertThat(show.hasSameVersion("v2")).isTrue();
    }
}
