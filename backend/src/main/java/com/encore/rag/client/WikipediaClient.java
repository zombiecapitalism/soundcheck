package com.encore.rag.client;

import com.encore.rag.RagProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MediaWiki API 클라이언트 — RAG 문서 소스(PRD 6.3: Wikipedia).
 * <ul>
 *   <li>검색으로 문서 제목을 찾고, extracts로 위키 마크업이 제거된 평문 본문을 받는다.</li>
 *   <li>출처 URL은 API가 주는 정식 URL(fullurl)을 쓴다 — 리다이렉트·특수문자 제목에도 안전.</li>
 *   <li>Wikimedia 익명 rate limit이 엄격하다(2026-07-30 실측: 연속 요청 몇 건에 429) —
 *       요청 간 최소 간격 + 429는 Retry-After 헤더를 존중해 재시도한다.</li>
 * </ul>
 */
@Component
public class WikipediaClient {

    private static final long MIN_INTERVAL_MILLIS = 1000;
    private static final int MAX_RETRIES = 3;
    /** Retry-After가 없거나 못 읽을 때의 대기(초). 상한은 서버가 큰 값을 줄 때의 보호선. */
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 5;
    private static final long MAX_RETRY_AFTER_SECONDS = 60;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private Long lastRequestAtNanos;

    public WikipediaClient(RestClient.Builder restClientBuilder, RagProperties properties,
                           ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent(properties.userAgentContact()))
                .build();
    }

    /** Wikimedia 정책: 식별 가능한 UA + 연락 수단 권고. 연락처는 환경변수로만 주입된다. */
    private static String userAgent(String contact) {
        return "EncoreSetlistStudy/0.1 (portfolio project"
                + (contact == null || contact.isBlank() ? "" : "; " + contact.trim()) + ")";
    }

    /** 문서 제목 검색 — 결과 제목 목록(관련도순). 없으면 빈 목록. */
    public List<String> search(String lang, String query, int limit) {
        JsonNode root = get(
                "https://{lang}.wikipedia.org/w/api.php?action=query&list=search&srsearch={q}&srlimit={n}&format=json&formatversion=2",
                lang, query, limit);
        List<String> titles = new ArrayList<>();
        for (JsonNode hit : root.path("query").path("search")) {
            titles.add(hit.path("title").asString());
        }
        return titles;
    }

    /** 문서 평문 본문 + 정식 URL. 문서가 없거나 본문이 비어 있으면 empty. */
    public Optional<WikipediaPage> fetchPage(String lang, String title) {
        // '|'(파이프)는 RestClient 기본 인코딩 모드(TEMPLATE_AND_VALUES)가 %7C로 인코딩한다
        JsonNode root = get(
                "https://{lang}.wikipedia.org/w/api.php?action=query&prop=extracts|info&explaintext=1&redirects=1&inprop=url&titles={t}&format=json&formatversion=2",
                lang, title);
        JsonNode pages = root.path("query").path("pages");
        if (pages.isEmpty()) {
            return Optional.empty();
        }
        JsonNode page = pages.get(0);
        String extract = page.path("extract").asString("");
        if (page.path("missing").asBoolean(false) || extract.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new WikipediaPage(
                page.path("title").asString(),
                extract,
                page.path("fullurl").asString("https://" + lang + ".wikipedia.org/wiki/" + title.replace(' ', '_'))));
    }

    private JsonNode get(String uriTemplate, Object... uriVariables) {
        for (int attempt = 0; ; attempt++) {
            throttle();
            try {
                String body = restClient.get()
                        .uri(uriTemplate, uriVariables)
                        .retrieve()
                        .body(String.class);
                return objectMapper.readTree(body);
            } catch (RestClientResponseException e) {
                // 429만 재시도한다 — 그 외 4xx는 다시 보내도 결과가 같다
                if (e.getStatusCode().value() != 429 || attempt >= MAX_RETRIES) {
                    throw e;
                }
                sleep(retryAfterMillis(e));
            }
        }
    }

    private static long retryAfterMillis(RestClientResponseException e) {
        long seconds = DEFAULT_RETRY_AFTER_SECONDS;
        String retryAfter = e.getResponseHeaders() != null
                ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (retryAfter != null) {
            try {
                seconds = Long.parseLong(retryAfter.trim());
            } catch (NumberFormatException ignored) {
                // 날짜 형식 Retry-After 등은 기본 대기로 대체
            }
        }
        return Math.max(0, Math.min(seconds, MAX_RETRY_AFTER_SECONDS)) * 1000;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Wikipedia 재시도 대기 중 인터럽트", ie);
        }
    }

    /** 요청 간 최소 간격 — 단순 정중함이지 rate limit 계약이 아니라 재시도 로직까지는 두지 않는다. */
    private synchronized void throttle() {
        if (lastRequestAtNanos != null) {
            long elapsedMillis = (System.nanoTime() - lastRequestAtNanos) / 1_000_000;
            long waitMillis = MIN_INTERVAL_MILLIS - elapsedMillis;
            if (waitMillis > 0) {
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Wikipedia 요청 대기 중 인터럽트", e);
                }
            }
        }
        lastRequestAtNanos = System.nanoTime();
    }
}
