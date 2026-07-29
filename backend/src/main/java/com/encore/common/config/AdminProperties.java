package com.encore.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 관리자 콘솔 인증 계정. 환경변수가 없으면 Spring이 "${VAR}" 문자열을 그대로 바인딩해
 * @NotBlank를 통과하므로(SetlistFmProperties와 같은 함정) 치환 여부를 직접 검사한다 —
 * 비밀번호가 리터럴 "${ADMIN_PASSWORD}"인 채로 뜨는 것은 인증이 없는 것과 같다.
 */
@Validated
@ConfigurationProperties(prefix = "encore.admin")
public record AdminProperties(

        @NotBlank String username,

        @NotBlank String password
) {

    public AdminProperties {
        requireResolved("encore.admin.username", username);
        requireResolved("encore.admin.password", password);
    }

    private static void requireResolved(String name, String value) {
        if (value != null && value.contains("${")) {
            throw new IllegalStateException(
                    "%s 값의 placeholder가 치환되지 않았습니다: %s — 대응하는 환경변수를 설정하세요. (.env 참고)"
                            .formatted(name, value));
        }
    }
}
