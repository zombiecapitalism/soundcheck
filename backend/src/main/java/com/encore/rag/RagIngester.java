package com.encore.rag;

import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.batch.CollectionCounts;
import com.encore.batch.CollectionLog;
import com.encore.batch.CollectionLogRepository;
import com.encore.batch.JobType;
import com.encore.rag.client.WikipediaClient;
import com.encore.rag.client.WikipediaPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * RAG 문서 수집 배치: 대상 아티스트마다
 * 아티스트 문서 1건 + 예측에 등장한 곡 문서들을 Wikipedia에서 수집 →
 * 청킹(500~800토큰) → 임베딩 → 저장한다.
 * <p>
 * 곡 검색이 앨범 문서로 이어지는 경우가 흔한데(수록곡 단독 문서가 없는 딥컷),
 * 그대로 앨범 문서(doc_type=ALBUM)로 저장한다 — 앨범 맥락도 답변 근거다.
 * 아티스트 단위 격리: 한 팀이 실패해도 나머지는 계속한다(수집 배치와 같은 원칙).
 */
@Service
public class RagIngester {

    private static final Logger log = LoggerFactory.getLogger(RagIngester.class);
    /** v1은 영어 위키만 — 록 밴드 문서 커버리지가 압도적이고, 한국어 생성은 LLM 몫이다. */
    private static final String LANG = "en";

    private final ArtistRepository artistRepository;
    private final WikipediaClient wikipediaClient;
    private final EmbeddingModel embeddingModel;
    private final RagDocumentRepository documentRepository;
    private final RagStore ragStore;
    private final CollectionLogRepository collectionLogRepository;
    private final JdbcClient jdbcClient;
    private final RagProperties properties;

    public RagIngester(ArtistRepository artistRepository, WikipediaClient wikipediaClient,
                       EmbeddingModel embeddingModel, RagDocumentRepository documentRepository,
                       RagStore ragStore, CollectionLogRepository collectionLogRepository,
                       JdbcClient jdbcClient, RagProperties properties) {
        this.artistRepository = artistRepository;
        this.wikipediaClient = wikipediaClient;
        this.embeddingModel = embeddingModel;
        this.documentRepository = documentRepository;
        this.ragStore = ragStore;
        this.collectionLogRepository = collectionLogRepository;
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    /** 대상 아티스트 전체 수집. 아티스트별로 실행 이력(EMBED)을 남긴다. */
    public List<CollectionLog> ingestAll() {
        List<CollectionLog> logs = new ArrayList<>();
        for (Artist artist : artistRepository.findByTargetTrue()) {
            logs.add(ingestArtist(artist));
        }
        return logs;
    }

    public CollectionLog ingestArtist(Artist artist) {
        Instant startedAt = Instant.now();
        int fetched = 0;
        int skipped = 0;
        try {
            List<SongRef> units = new ArrayList<>();
            units.add(new SongRef(null, null)); // 아티스트 단위 문서
            units.addAll(predictedSongs(artist));

            for (SongRef unit : units) {
                switch (ingestUnit(artist, unit)) {
                    case STORED -> fetched++;
                    case SKIPPED -> skipped++;
                }
            }
            // counts 의미: fetched=저장된 문서 수, updated=저장된 청크 수, skipped=중복·검색 실패
            CollectionLog result = CollectionLog.success(JobType.EMBED, artist.getMbid(),
                    CollectionCounts.builder()
                            .fetched(fetched)
                            .updated(chunkCountSince(artist, startedAt))
                            .skipped(skipped)
                            .build(),
                    startedAt);
            return collectionLogRepository.save(result);
        } catch (RuntimeException e) {
            log.warn("RAG 수집 실패: {}", artist.getName(), e);
            return collectionLogRepository.save(
                    CollectionLog.failed(JobType.EMBED, artist.getMbid(), e.getMessage(), startedAt));
        }
    }

    private enum UnitResult { STORED, SKIPPED }

    private UnitResult ingestUnit(Artist artist, SongRef unit) {
        boolean isArtistUnit = unit.songKey() == null;
        String query = isArtistUnit
                ? artist.getName() + " band"
                : unit.songName() + " " + artist.getName() + " song";

        List<String> titles = wikipediaClient.search(LANG, query, 1);
        if (titles.isEmpty()) {
            return UnitResult.SKIPPED;
        }
        Optional<WikipediaPage> pageResult = wikipediaClient.fetchPage(LANG, titles.getFirst());
        if (pageResult.isEmpty()) {
            return UnitResult.SKIPPED;
        }
        WikipediaPage page = pageResult.get();
        // 같은 출처는 한 번만 — 여러 곡이 같은 앨범 문서로 이어져도 중복 저장하지 않는다
        if (documentRepository.existsByArtistMbidAndSourceUrl(artist.getMbid(), page.url())) {
            return UnitResult.SKIPPED;
        }

        String content = truncate(page.extract(), properties.maxContentChars());
        List<RagChunker.Chunk> chunks = RagChunker.chunk(content, properties.chunkTargetTokens());
        if (chunks.isEmpty()) {
            return UnitResult.SKIPPED;
        }
        List<float[]> embeddings = embeddingModel.embed(
                chunks.stream().map(RagChunker.Chunk::content).toList());

        ragStore.save(RagDocument.builder()
                        .artistMbid(artist.getMbid())
                        .songKey(isArtistUnit ? null : unit.songKey())
                        .docType(classify(isArtistUnit, page.title()))
                        .title(page.title())
                        .sourceName("Wikipedia")
                        .sourceUrl(page.url())
                        .build(),
                chunks, embeddings);
        return UnitResult.STORED;
    }

    /** 예측에 등장한 곡들 — 사용자가 실제로 볼 곡만 수집한다(비용 통제). */
    private List<SongRef> predictedSongs(Artist artist) {
        return jdbcClient.sql("""
                        SELECT DISTINCT p.song_key, p.song_name
                        FROM prediction p
                        JOIN target_event e ON e.id = p.target_event_id
                        WHERE e.artist_mbid = :mbid
                        ORDER BY p.song_key
                        LIMIT :limit
                        """)
                .param("mbid", artist.getMbid())
                .param("limit", properties.maxSongsPerArtist())
                .query((rs, rowNum) -> new SongRef(rs.getString("song_key"), rs.getString("song_name")))
                .list();
    }

    /** 저장된 청크 수 집계 — 이력 카운트(updated)용. */
    private int chunkCountSince(Artist artist, Instant since) {
        Integer count = jdbcClient.sql("""
                        SELECT count(*) FROM rag_chunk c
                        JOIN rag_document d ON d.id = c.document_id
                        WHERE d.artist_mbid = :mbid AND d.collected_at >= :since
                        """)
                .param("mbid", artist.getMbid())
                .param("since", java.sql.Timestamp.from(since))
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }

    private static DocType classify(boolean isArtistUnit, String pageTitle) {
        if (isArtistUnit) {
            return DocType.ARTIST;
        }
        String lower = pageTitle.toLowerCase(Locale.ROOT);
        return lower.contains("album)") || lower.endsWith(" ep)") || lower.contains("(ep)")
                ? DocType.ALBUM : DocType.SONG;
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private record SongRef(String songKey, String songName) {
    }
}
