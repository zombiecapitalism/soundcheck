package com.encore.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * setlist.fm 연동 설정.
 * <p>
 * 값이 비어 있으면 첫 API 호출 시점이 아니라 기동 시점에 실패하도록 검증을 건다.
 * 배치가 한참 돌다가 인증 오류로 죽는 것보다 아예 뜨지 않는 편이 낫다.
 */
@Validated
@ConfigurationProperties(prefix = "setlist-fm")
public record SetlistFmProperties(

        @NotBlank String baseUrl,

        @NotBlank String apiKey
) {

    public SetlistFmProperties {
        requireResolved("setlist-fm.base-url", baseUrl);
        requireResolved("setlist-fm.api-key", apiKey);
    }

    /**
     * 환경변수가 없으면 Spring은 예외를 던지지 않고 "${VAR}" 문자열을 그대로 바인딩한다.
     * 비어 있지 않으므로 {@code @NotBlank}는 통과해버려서, 여기서 직접 막지 않으면
     * 잘못된 키를 들고 기동한 뒤 실제 호출에서야 실패한다.
     */
    private static void requireResolved(String name, String value) {
        if (value != null && value.contains("${")) {
            throw new IllegalStateException(
                    "%s 값의 placeholder가 치환되지 않았습니다: %s — 대응하는 환경변수를 설정하세요. (.env 참고)"
                            .formatted(name, value));
        }
    }
}
