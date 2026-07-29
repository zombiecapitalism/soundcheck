package com.encore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 전용 PostgreSQL. 개발용 docker-compose DB에 붙지 않으므로
 * 테스트가 개발 데이터와 격리되고, Docker 데몬만 있으면 CI에서도 그대로 돈다.
 * <p>
 * 컨테이너는 static 싱글턴이다 — 테스트 슬라이스마다 Spring 컨텍스트가 갈리는데,
 * 컨텍스트마다 새 컨테이너를 띄우면 그만큼 느려진다. 한 JVM에서 하나만 띄워 공유하고
 * 종료는 Testcontainers(Ryuk)가 JVM 종료 시 처리한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            // 운영과 같은 이미지를 쓴다. docker-compose.yml과 태그를 맞출 것.
            DockerImageName.parse("pgvector/pgvector:0.8.5-pg16-trixie")
                    .asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
