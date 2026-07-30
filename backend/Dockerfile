# syntax=docker/dockerfile:1
# 멀티 스테이지: 컨테이너 안에서 빌드해 호스트 JDK에 의존하지 않는다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Windows 체크아웃의 CRLF가 셸 스크립트를 깨는 것 방지
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar --console=plain

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
