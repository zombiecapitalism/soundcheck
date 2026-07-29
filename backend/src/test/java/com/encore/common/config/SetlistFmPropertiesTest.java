package com.encore.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.WebApplicationType;
import com.encore.BackendApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SetlistFmPropertiesTest {

    @Autowired
    private SetlistFmProperties properties;

    @Test
    void bindsFromEnvironment() {
        assertThat(properties.baseUrl()).isEqualTo("https://api.setlist.fm/rest");
        assertThat(properties.apiKey()).isNotBlank();
    }

    /**
     * 환경변수가 없으면 Spring은 "${VAR}"를 그대로 바인딩하고 @NotBlank도 통과시킨다.
     * 그 상태로 기동되면 실제 API 호출에서야 실패하므로, 기동 자체가 막히는지 확인한다.
     */
    @Test
    void failsToStartWhenPlaceholderIsUnresolved() {
        SpringApplication app = new SpringApplication(BackendApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);

        assertThatThrownBy(() ->
                app.run("--setlist-fm.api-key=${DEFINITELY_MISSING_VAR_XYZ}").close())
                .hasStackTraceContaining("placeholder가 치환되지 않았습니다");
    }
}
