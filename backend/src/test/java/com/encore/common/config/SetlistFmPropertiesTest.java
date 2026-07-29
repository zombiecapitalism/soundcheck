package com.encore.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import com.encore.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SetlistFmPropertiesTest {

    @Autowired
    private SetlistFmProperties properties;

    /**
     * "yml → 레코드 바인딩 경로가 동작하고 값이 치환돼 있다"를 검증한다.
     * 키 값 자체는 고정하지 않는다 — 환경변수가 있으면 그쪽이 테스트 리소스의 더미보다
     * 우선하므로, 특정 값을 단언하면 어느 한쪽 환경에서 반드시 깨진다.
     * 더미(application.properties)는 환경변수가 없는 곳에서도 컨텍스트가 뜨게 하는 안전망이다.
     */
    @Test
    void bindsFromConfiguration() {
        assertThat(properties.baseUrl()).isEqualTo("https://api.setlist.fm/rest");
        assertThat(properties.apiKey()).isNotBlank().doesNotContain("${");
    }

    /**
     * 환경변수가 없으면 Spring은 "${VAR}"를 그대로 바인딩하고 @NotBlank도 통과시킨다.
     * 레코드 바인딩은 이 생성자를 그대로 호출하므로, 생성자가 던지면 기동도 실패한다.
     * (전체 컨텍스트를 띄워 확인하면 테스트가 DB·환경변수에 다시 결합되므로 생성자 단위로 검증한다.)
     */
    @Test
    void rejectsUnresolvedPlaceholder() {
        assertThatThrownBy(() -> newProperties("https://api.setlist.fm/rest", "${SETLIST_FM_API_KEY}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder가 치환되지 않았습니다");

        assertThatThrownBy(() -> newProperties("${BASE_URL}", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setlist-fm.base-url");
    }

    private static SetlistFmProperties newProperties(String baseUrl, String apiKey) {
        return new SetlistFmProperties(baseUrl, apiKey, Duration.ZERO, 0, Duration.ZERO);
    }
}
