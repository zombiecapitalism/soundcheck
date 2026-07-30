package com.encore.playlist;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** YouTube Data API v3 설정 — api-key가 비어 있으면 재생목록 기능은 503으로 비활성. */
@ConfigurationProperties("youtube")
public record YoutubeProperties(String apiKey, String baseUrl) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
