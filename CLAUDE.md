# CLAUDE.md

이 파일은 이 저장소에서 작업할 때 항상 참조한다.

## 프로젝트 개요

**Soundcheck** — 내한 페스티벌 셋리스트 예측 & 예습 서비스.

setlist.fm의 과거 공연 데이터를 수집해 "이번 공연에서 어떤 곡을 연주할 확률이 높은가"를 예측하고, 각 곡에 대해 RAG 기반 배경 설명을 제공한다.

- **1차 타겟**: 2026 부산국제록페스티벌 (2026-10-02 ~ 10-04, 삼락생태공원) 해외 라인업
- **성격**: 재취직용 포트폴리오 프로젝트. 실사용자 공개 목표
- **비목표**: 수익화, 커뮤니티 기능, 티켓 예매

## 기술 스택

| 영역 | 스택 |
|------|------|
| Backend | Java 21, Spring Boot 4.x, Spring Data JPA, Spring AI 2.x (Jackson 3 — `tools.jackson`) |
| Build | Gradle (Kotlin DSL) |
| DB | PostgreSQL 16 + pgvector |
| Batch | Spring Scheduler (규모 커지면 Spring Batch로 전환) |
| Frontend | React 19 + TypeScript + Vite, TanStack Query |
| 로컬 인프라 | Docker Compose (postgres/pgvector) |

> 라이브러리 버전은 착수 시점의 최신 안정 버전을 확인해서 쓸 것. 이 문서의 버전 표기를 맹신하지 말 것.

## 디렉터리 구조

```
/backend          Spring Boot 애플리케이션
  /src/main/java/com/encore
    /artist       아티스트 도메인
    /setlist      공연/셋리스트 도메인 (수집 포함)
    /prediction   예측 로직 (계산기·정확도·예상 셋리스트·유사 공연·변화 요약)
    /rag          RAG 파이프라인
    /chat         RAG Chat (tool calling, 레이트리밋)
    /llm          LLM 계측 (llm_call_log, 비용 추정)
    /playlist     YouTube 재생목록 (영상 ID 캐시)
    /batch        배치 실행 이력 (collection_log — 수집/예측/임베딩 공용)
    /pipeline     일일 파이프라인 (수집 → 매칭 → 예측) + 스케줄
    /api          REST 어댑터 (컨트롤러, 응답 DTO, Problem Detail 변환)
    /common       공통(설정, 예외, 응답 래퍼)
/frontend         React SPA
/docs             설계 문서 (PRD, 스키마)
docker-compose.yml
```

## 핵심 도메인 규칙 (반드시 지킬 것)

1. **아티스트 식별자는 MusicBrainz MBID**. 밴드 이름을 키로 쓰지 않는다.
2. **`versionId`로 변경 감지**. setlist.fm은 위키 방식이라 같은 `id`라도 내용이 바뀔 수 있다. DB의 `version_id`와 다를 때만 재적재하고, 같으면 스킵한다.
3. **`eventDate`는 `dd-MM-yyyy` 문자열**이다. ISO가 아니다. 파싱해서 DATE로 저장하고 문자열 정렬을 하지 않는다.
4. **`tape: true`인 곡은 예측 집계에서 제외**한다. 실제 연주가 아니라 입·퇴장 시 튼 음원이다.
5. **곡명은 원본(`song_name`)과 정규화(`song_key`)를 분리 저장**한다. 정규화는 손실 변환이므로 원본을 반드시 남긴다. 집계는 `song_key` 기준.
6. **페스티벌 셋과 단독 공연 셋을 구분**한다(`show_type`). 페스티벌은 곡 수가 짧아서(보통 60~90분) 단독 공연 데이터로만 예측하면 곡 수를 과대 추정한다.
7. **응답 원본 JSON을 `raw_json`(JSONB)에 보관**한다. 파싱 로직을 고쳐도 재수집 없이 재처리할 수 있어야 한다.
8. **RAG 답변은 검색된 출처에 근거한 내용만 생성**한다. 근거가 없으면 "정보 없음"으로 응답한다. 출처 URL을 항상 함께 반환한다.
9. **가사 원문을 저장하거나 출력하지 않는다.** 해석·배경 설명만 다룬다.

## 외부 API

### setlist.fm

- Base URL: `https://api.setlist.fm/rest`
- 인증: `x-api-key` 헤더
- **`Accept: application/json` 헤더 필수** (미지정 시 XML 응답)
- 비상업 프로젝트에 한해 무료. 서비스 화면에 출처를 표기한다.
- 사용 엔드포인트
  - `GET /1.0/search/artists?artistName={name}` — MBID 획득
  - `GET /1.0/artist/{mbid}/setlists?p={page}` — 아티스트별 셋리스트 (최근순)
  - `GET /1.0/setlist/{setlistId}` — 단건 조회
- 리스트 응답은 `total` / `itemsPerPage` / `page` 로 페이징
- Rate limit 존재. 요청 간 지연을 두고, 429는 지수 백오프로 재시도한다.

### setlist 응답 구조 (문서 기준, 실응답으로 검증 필요)

```
setlist
├─ id, versionId, eventDate, lastUpdated, url, info
├─ artist : { mbid, name, sortName, url }
├─ venue  : { id, name, city: { name, country: { code, name } } }
├─ tour   : { name }                 // 없을 수 있음
└─ sets.set[] : { name, encore, song[] }
      └─ song[] : { name, cover, with, info, tape }
```

## 보안 / 설정

- API 키, DB 비밀번호는 **절대 커밋하지 않는다.** 환경변수로 주입하고 `.env.example`만 커밋한다.
- `application.yml`에는 `${SETLIST_FM_API_KEY}` 형태로만 참조한다.

## 작업 방식

- 커밋은 기능 단위로 잘게. 커밋 메시지는 한글 또는 영문 일관되게.
- 새 기능은 테스트를 함께 작성한다. 특히 곡명 정규화, 날짜 파싱, 예측 계산은 단위 테스트 필수.
- 외부 API 호출은 테스트에서 실제로 때리지 않는다. 응답 fixture JSON을 만들어 사용한다.
- 스키마 변경 시 `/docs/setlist-schema.md`도 함께 갱신한다.
- 확실하지 않은 외부 API 스펙은 추측해서 구현하지 말고, 검증이 필요한 지점을 먼저 알려준다.
