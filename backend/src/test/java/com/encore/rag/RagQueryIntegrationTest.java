package com.encore.rag;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector 유사도 검색 통합 테스트 — V6 마이그레이션(vector 확장·HNSW)과
 * 메타 필터·minScore 동작을 실제 PostgreSQL(pgvector 컨테이너)에서 검증한다.
 * 임베딩 모델은 부르지 않는다 — 손으로 만든 단위 벡터를 쓴다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RagQueryIntegrationTest {

    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private RagStore ragStore;
    @Autowired
    private RagChunkRepository chunkRepository;

    private UUID mbid;

    /** i번째 축의 단위 벡터 — 서로 코사인 유사도 0, 자기 자신과 1. */
    private static float[] axis(int i) {
        float[] v = new float[1536];
        v[i] = 1f;
        return v;
    }

    /** 두 축 사이 벡터 — axis(a)와 유사도 cos(45°)≈0.707. */
    private static float[] between(int a, int b) {
        float[] v = new float[1536];
        v[a] = 0.7071f;
        v[b] = 0.7071f;
        return v;
    }

    @BeforeEach
    void setUp() {
        mbid = UUID.randomUUID();
        artistRepository.saveAndFlush(Artist.builder().mbid(mbid).name("Megadeth").target(true).build());
    }

    private void saveDoc(String songKey, DocType docType, String title, String url, float[]... embeddings) {
        List<RagChunker.Chunk> chunks = new java.util.ArrayList<>();
        List<float[]> vectors = new java.util.ArrayList<>();
        for (int i = 0; i < embeddings.length; i++) {
            chunks.add(new RagChunker.Chunk(title + " 청크" + i, 100));
            vectors.add(embeddings[i]);
        }
        ragStore.save(RagDocument.builder()
                .artistMbid(mbid).songKey(songKey).docType(docType)
                .title(title).sourceName("Wikipedia").sourceUrl(url)
                .build(), chunks, vectors);
    }

    @Test
    void ranksBySimilarityAndLimitsToTopK() {
        saveDoc("holy wars", DocType.SONG, "가까운 문서", "url-close", axis(0));
        saveDoc("holy wars", DocType.SONG, "비스듬한 문서", "url-mid", between(0, 1));

        List<RetrievedChunk> results = chunkRepository.search(mbid, "holy wars", axis(0), 1, 0.35);

        // topK=1이면 가장 유사한 청크만
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().documentTitle()).isEqualTo("가까운 문서");
        assertThat(results.getFirst().score()).isGreaterThan(0.99);
        assertThat(results.getFirst().sourceUrl()).isEqualTo("url-close");
    }

    @Test
    void filtersOtherSongsButKeepsArtistAndAlbumDocs() {
        saveDoc("holy wars", DocType.SONG, "이 곡 문서", "url-song", axis(0));
        saveDoc("tornado of souls", DocType.SONG, "다른 곡 문서", "url-other", axis(0));
        saveDoc(null, DocType.ARTIST, "밴드 문서", "url-artist", axis(0));
        saveDoc("tornado of souls", DocType.ALBUM, "앨범 문서", "url-album", axis(0));

        List<RetrievedChunk> results = chunkRepository.search(mbid, "holy wars", axis(0), 10, 0.35);

        // 다른 곡의 SONG 문서만 빠진다 — 앨범·밴드 문서는 공용 배경 근거다
        assertThat(results).extracting(RetrievedChunk::documentTitle)
                .containsExactlyInAnyOrder("이 곡 문서", "밴드 문서", "앨범 문서");
    }

    @Test
    void excludesChunksBelowMinScore() {
        saveDoc("holy wars", DocType.SONG, "관련 문서", "url-1", axis(0), axis(1));

        List<RetrievedChunk> results = chunkRepository.search(mbid, "holy wars", axis(0), 10, 0.35);

        // axis(1) 청크는 유사도 0 — minScore에 걸려 근거에서 빠져야 한다
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().content()).contains("청크0");
    }

    @Test
    void filtersByArtist() {
        saveDoc("holy wars", DocType.SONG, "내 문서", "url-mine", axis(0));

        List<RetrievedChunk> results = chunkRepository.search(
                UUID.randomUUID(), "holy wars", axis(0), 10, 0.0);

        assertThat(results).isEmpty();
    }
}
