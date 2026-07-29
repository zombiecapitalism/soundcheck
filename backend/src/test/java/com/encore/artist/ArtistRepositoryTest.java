package com.encore.artist;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ArtistRepositoryTest {

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private EntityManager entityManager;

    private Artist megadeth() {
        return Artist.builder()
                .mbid(UUID.randomUUID())
                .name("Megadeth")
                .build();
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsAreOmitted() {
        Artist saved = artistRepository.saveAndFlush(megadeth());
        entityManager.clear();

        Artist reloaded = artistRepository.findById(saved.getMbid()).orElseThrow();
        assertThat(reloaded.isTarget()).isFalse();
        assertThat(reloaded.getSortName()).isNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsOnlyCollectionTargets() {
        Artist target = megadeth();
        target.markAsCollectionTarget();
        artistRepository.saveAndFlush(target);
        artistRepository.saveAndFlush(megadeth());

        assertThat(artistRepository.findByTargetTrue())
                .extracting(Artist::getMbid)
                .containsExactly(target.getMbid());
    }

    @Test
    void updateProfileKeepsIdentifier() {
        Artist artist = artistRepository.saveAndFlush(megadeth());
        UUID originalMbid = artist.getMbid();

        artist.updateProfile("MEGADETH", "Megadeth", "https://www.setlist.fm/setlists/megadeth-3bd6dc6d.html");
        artistRepository.flush();

        assertThat(artist.getMbid()).isEqualTo(originalMbid);
        assertThat(artist.getName()).isEqualTo("MEGADETH");
        assertThat(artist.getSetlistFmUrl()).contains("setlist.fm");
    }
}
