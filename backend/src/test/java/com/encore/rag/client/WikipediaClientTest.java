package com.encore.rag.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실제 API를 호출하지 않는다(CLAUDE.md) — MockRestServiceServer + fixture JSON.
 * fixtures/wikipedia/*.json은 2026-07-30 en.wikipedia.org 실응답(formatversion=2)에서 발췌.
 */
class WikipediaClientTest {

    private MockRestServiceServer server;
    private WikipediaClient client;

    private static com.encore.rag.RagProperties properties(String contact) {
        return new com.encore.rag.RagProperties(5, 0.35, 650, 40_000, 30, contact);
    }

    private WikipediaClient newClient(String contact) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new WikipediaClient(builder, properties(contact), JsonMapper.builder().build());
    }

    @BeforeEach
    void setUp() {
        this.client = newClient("");
    }

    private static String fixture(String name) {
        try {
            return new ClassPathResource("fixtures/wikipedia/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void searchEncodesQueryAndReturnsTitles() {
        server.expect(requestTo(allOf(
                        containsString("https://en.wikipedia.org/w/api.php"),
                        containsString("srsearch=Afterlife%20Avenged%20Sevenfold%20song"),
                        containsString("srlimit=1"))))
                .andExpect(method(GET))
                .andExpect(header("User-Agent", containsString("SoundcheckSetlistStudy")))
                .andRespond(withSuccess(fixture("search.json"), MediaType.APPLICATION_JSON));

        List<String> titles = client.search("en", "Afterlife Avenged Sevenfold song", 1);

        assertThat(titles).containsExactly("Afterlife (Avenged Sevenfold song)");
    }

    @Test
    void fetchPageReturnsPlainTextAndCanonicalUrl() {
        // prop=extracts|info의 파이프가 %7C로 인코딩되어야 한다(미인코딩 '|'는 비합법 문자).
        // 템플릿 값 인코딩은 괄호도 %28/%29로 인코딩한다(2026-07-30 실측) — MediaWiki는 문제없이 받는다.
        server.expect(requestTo(allOf(
                        containsString("prop=extracts%7Cinfo"),
                        containsString("titles=Afterlife%20%28Avenged%20Sevenfold%20song%29"))))
                .andExpect(method(GET))
                .andRespond(withSuccess(fixture("page.json"), MediaType.APPLICATION_JSON));

        Optional<WikipediaPage> page = client.fetchPage("en", "Afterlife (Avenged Sevenfold song)");

        assertThat(page).hasValueSatisfying(p -> {
            assertThat(p.title()).isEqualTo("Afterlife (Avenged Sevenfold song)");
            assertThat(p.extract()).startsWith("\"Afterlife\" is a song");
            assertThat(p.extract()).doesNotContain("<span"); // 평문이어야 한다 — 마크업 없음
            assertThat(p.url()).isEqualTo("https://en.wikipedia.org/wiki/Afterlife_(Avenged_Sevenfold_song)");
        });
    }

    @Test
    void missingPageIsEmpty() {
        server.expect(requestTo(containsString("titles=No%20Such%20Page%20Xyz")))
                .andRespond(withSuccess(fixture("page-missing.json"), MediaType.APPLICATION_JSON));

        assertThat(client.fetchPage("en", "No Such Page Xyz")).isEmpty();
    }

    /** Wikimedia 정책 권고: UA에 연락 수단. 환경변수로 주입될 때만 붙는다. */
    @Test
    void appendsContactToUserAgentWhenConfigured() {
        WikipediaClient withContact = newClient("mailto:ops@example.com");
        server.expect(requestTo(containsString("srsearch=Pixies")))
                .andExpect(header("User-Agent",
                        containsString("SoundcheckSetlistStudy/0.1 (portfolio project; mailto:ops@example.com)")))
                .andRespond(withSuccess(fixture("search.json"), MediaType.APPLICATION_JSON));

        withContact.search("en", "Pixies", 1);
        server.verify();
    }

    @Test
    void retriesOn429HonoringRetryAfter() {
        server.expect(requestTo(containsString("srsearch=Megadeth")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .headers(retryAfterZero())
                        .body("You are making too many requests to the API."));
        server.expect(requestTo(containsString("srsearch=Megadeth")))
                .andRespond(withSuccess(fixture("search.json"), MediaType.APPLICATION_JSON));

        assertThat(client.search("en", "Megadeth", 1)).isNotEmpty();
    }

    private static org.springframework.http.HttpHeaders retryAfterZero() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Retry-After", "0"); // 테스트가 실제로 기다리지 않게 0초
        return headers;
    }

    @Test
    void emptySearchIsEmptyList() {
        server.expect(requestTo(containsString("srsearch=zzzz")))
                .andRespond(withSuccess("""
                        {"batchcomplete":true,"query":{"searchinfo":{"totalhits":0},"search":[]}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.search("en", "zzzz", 1)).isEmpty();
    }
}
