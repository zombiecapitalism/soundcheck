package com.encore.rag;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.rag.SongExplanationService.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 곡 설명 DB 캐시 — 스트림 완주 시 저장, 재조회는 LLM 없이 캐시로, 수집 시 무효화.
 * LLM은 deep-stub 목이다(실호출 금지).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SongExplanationCacheTest {

    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private SongExplanationCache cache;

    private UUID mbid;

    @BeforeEach
    void setUp() {
        mbid = UUID.randomUUID();
        artistRepository.saveAndFlush(Artist.builder().mbid(mbid).name("Megadeth").target(true).build());
    }

    private static RagRetriever retrieverReturning(List<RetrievedChunk> chunks) {
        RagRetriever retriever = mock(RagRetriever.class);
        when(retriever.retrieve(any(), anyString(), anyString())).thenReturn(chunks);
        return retriever;
    }

    private SongExplanationService serviceWith(RagRetriever retriever, Flux<String> generated) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(generated);
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Mockito.RETURNS_SELF);
        when(builder.build()).thenReturn(chatClient);
        // 계측(E9)은 부가 기능 — 이 테스트의 관심사가 아니므로 목으로 무시한다
        return new SongExplanationService(retriever, cache, builder,
                mock(com.encore.llm.LlmCallRecorder.class));
    }

    private static final List<RetrievedChunk> CHUNKS = List.of(
            new RetrievedChunk("배경 내용", "Holy Wars", "Wikipedia", "url-1", 0.9));

    @Test
    void cachesAfterFullStreamAndServesNextRequestWithoutLlm() {
        SongExplanationService first = serviceWith(retrieverReturning(CHUNKS),
                Flux.just("첫 ", "생성 본문"));
        String streamed = String.join("",
                first.explain(mbid, "holy wars", "Holy Wars", "Megadeth").tokens()
                        .collectList().block());
        assertThat(streamed).isEqualTo("첫 생성 본문");

        // 두 번째 서비스의 LLM은 다른 답을 주지만, 캐시가 맞으면 호출 자체가 없어야 한다
        SongExplanationService second = serviceWith(retrieverReturning(CHUNKS),
                Flux.just("두 번째 생성 — 캐시가 맞으면 보이면 안 됨"));
        SongExplanationService.Explanation cached =
                second.explain(mbid, "holy wars", "Holy Wars", "Megadeth");

        assertThat(String.join("", cached.tokens().collectList().block())).isEqualTo("첫 생성 본문");
        assertThat(cached.sources()).containsExactly(new Source("Wikipedia", "url-1", "Holy Wars"));
    }

    @Test
    void doesNotCacheNoInfoFastPath() {
        SongExplanationService service = serviceWith(retrieverReturning(List.of()),
                Flux.just("호출되면 안 됨"));

        String streamed = String.join("",
                service.explain(mbid, "unknown song", "Unknown", "Megadeth").tokens()
                        .collectList().block());

        assertThat(streamed).isEqualTo(ExplanationPrompts.NO_INFO);
        // LLM을 부르지 않은 응답은 캐시하지 않는다 — 문서가 수집되면 곧바로 반영돼야 한다
        assertThat(cache.find(mbid, "unknown song")).isEmpty();
    }

    @Test
    void interruptedStreamIsNotCached() {
        SongExplanationService service = serviceWith(retrieverReturning(CHUNKS),
                Flux.concat(Flux.just("일부만"), Flux.error(new IllegalStateException("끊김"))));

        service.explain(mbid, "holy wars", "Holy Wars", "Megadeth").tokens()
                .onErrorComplete().collectList().block();

        assertThat(cache.find(mbid, "holy wars")).isEmpty();
    }

    @Test
    void duplicateSaveIsIgnoredAndEvictClearsArtist() {
        List<Source> sources = List.of(new Source("Wikipedia", "url-1", "Holy Wars"));
        cache.save(mbid, "holy wars", sources, "본문");
        cache.save(mbid, "holy wars", sources, "동시 요청의 두 번째 저장"); // 유니크 충돌 — 무시돼야 한다

        assertThat(cache.find(mbid, "holy wars")).hasValueSatisfying(cached -> {
            assertThat(cached.content()).isEqualTo("본문");
            assertThat(cached.sources()).isEqualTo(sources);
        });

        cache.evictArtist(mbid);
        assertThat(cache.find(mbid, "holy wars")).isEmpty();
    }
}
