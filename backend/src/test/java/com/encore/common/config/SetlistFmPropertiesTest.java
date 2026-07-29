package com.encore.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SetlistFmPropertiesTest {

    @Autowired
    private SetlistFmProperties properties;

    /**
     * 키 값은 테스트 리소스의 더미다. 진짜 키가 아니라 "yml → 레코드 바인딩 경로가 동작한다"를
     * 검증하는 것이므로, 이 테스트는 실제 시크릿 없이 돌아야 한다.
     */
    @Test
    void bindsFromConfiguration() {
        assertThat(properties.baseUrl()).isEqualTo("https://api.setlist.fm/rest");
        assertThat(properties.apiKey()).isEqualTo("test-dummy-key");
    }

    /**
     * 환경변수가 없으면 Spring은 "${VAR}"를 그대로 바인딩하고 @NotBlank도 통과시킨다.
     * 레코드 바인딩은 이 생성자를 그대로 호출하므로, 생성자가 던지면 기동도 실패한다.
     * (전체 컨텍스트를 띄워 확인하면 테스트가 DB·환경변수에 다시 결합되므로 생성자 단위로 검증한다.)
     */
    @Test
    void rejectsUnresolvedPlaceholder() {
        assertThatThrownBy(() -> new SetlistFmProperties("https://api.setlist.fm/rest", "${SETLIST_FM_API_KEY}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder가 치환되지 않았습니다");

        assertThatThrownBy(() -> new SetlistFmProperties("${BASE_URL}", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setlist-fm.base-url");
    }
}
