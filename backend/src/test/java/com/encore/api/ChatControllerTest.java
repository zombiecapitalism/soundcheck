package com.encore.api;

import com.encore.TestcontainersConfiguration;
import com.encore.artist.Artist;
import com.encore.artist.ArtistRepository;
import com.encore.chat.ChatService;
import com.encore.prediction.TargetEvent;
import com.encore.prediction.TargetEventRepository;
import com.encore.rag.SongExplanationService.Source;
import com.encore.setlist.ShowType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * Chat SSE 계약 — 곡 설명과 달리 출처가 도구 실행 후 확정되므로
 * 순서가 delta* → sources → done 이어야 한다. LLM은 목이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private TargetEventRepository targetEventRepository;

    @MockitoBean
    private ChatService chatService;

    private TargetEvent persistEvent() {
        Artist artist = artistRepository.saveAndFlush(Artist.builder()
                .mbid(UUID.randomUUID()).name("Megadeth").target(true).build());
        return targetEventRepository.saveAndFlush(TargetEvent.builder()
                .artist(artist).eventName("챗 이벤트").eventDate(LocalDate.of(2099, 10, 2))
                .expectedShowType(ShowType.FESTIVAL).build());
    }

    @Test
    void streamsDeltasThenSourcesThenDone() throws Exception {
        TargetEvent event = persistEvent();
        when(chatService.chat(any(), any())).thenReturn(new ChatService.ChatStream(
                Flux.just("확률 ", "92%입니다."),
                () -> List.of(new Source("Soundcheck", "", "예측 데이터 기준"))));

        MvcResult result = mockMvc.perform(post("/api/events/{id}/chat", event.getId())
                        .contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user","content":"확률은?"}]}"""))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 스트림이 끝나기 전에 본문을 읽으면 잘린다 — async 결과를 기다린다
        result.getAsyncResult();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getContentType()).contains("text/event-stream");
        // 출처는 도구 실행이 끝나야 확정 — 곡 설명(sources 먼저)과 반대 순서다
        assertThat(body).containsSubsequence(
                "event:delta", "\"확률 \"",
                "event:delta", "\"92%입니다.\"",
                "event:sources", "예측 데이터 기준",
                "event:done");
    }

    @Test
    void generationFailureBecomesErrorEventNotBrokenStream() throws Exception {
        TargetEvent event = persistEvent();
        when(chatService.chat(any(), any())).thenReturn(new ChatService.ChatStream(
                Flux.error(new IllegalStateException("LLM 호출 실패")),
                List::of));

        MvcResult result = mockMvc.perform(post("/api/events/{id}/chat", event.getId())
                        .contentType("application/json")
                        .content("""
                                {"messages":[{"role":"user","content":"질문"}]}"""))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 스트림이 끝나기 전에 본문을 읽으면 잘린다 — async 결과를 기다린다
        result.getAsyncResult();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:error");
        assertThat(body).doesNotContain("LLM 호출 실패"); // 내부 메시지를 노출하지 않는다
    }
}
