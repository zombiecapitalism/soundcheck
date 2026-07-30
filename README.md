# Encore

내한 페스티벌 셋리스트 예측 & 예습 서비스. 자세한 배경은 `docs/setlist-prd.md`, DB/API 설계는 `docs/setlist-schema.md` 참고.

## 주요 기능

- **예측 셋리스트** — setlist.fm 과거 공연을 수집해 곡별 연주 확률을 계산. 최신 가중치·페스티벌/단독 구분 가중치, "최근 20회 중 19회 연주" 근거 수치 표시
- **적중률 자가 검증** — 공연 전 예측을 스냅샷으로 고정하고, 공연 후 실제 셋리스트와 비교해 적중률(Precision@K)을 공개
- **곡별 연주 타임라인** — 최근 공연에서 언제·몇 번째로 연주됐는지(미연주 공백 포함), 고정곡/로테이션곡/앙코르 단골 해석
- **내한 자동 감지** — 별도 크롤링 없이 수집 데이터의 한국(KR) 미래 공연을 감지해 원클릭 등록
- **관리자 콘솔** — 아티스트 검색·등록, 이벤트 등록, 배치 수동 트리거·이력 대시보드 (Basic 인증)
- **일일 파이프라인** — 매일 새벽 수집 → 적중률 매칭 → 예측 재계산이 자동으로 이어진다

## 스택

- Backend: Java 21, Spring Boot 4.1.0, Gradle(Kotlin DSL), Lombok
- DB: PostgreSQL 16 + pgvector (Docker), Flyway 마이그레이션
- Frontend: React 19 + TypeScript + Vite, TanStack Query, React Router

## 로컬 실행

### 1. 환경 변수 설정

```bash
cp .env.example .env
```

`.env`에 아래 값을 채운다.

| 변수 | 설명 |
|------|------|
| `SETLIST_FM_API_KEY` | setlist.fm에서 발급받은 API 키 (https://www.setlist.fm/settings/api) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | 기본값(localhost / 5432 / encore) 그대로 사용 가능 |
| `DB_USERNAME` / `DB_PASSWORD` | 로컬 개발용 계정 — 원하는 값으로 지정 (docker-compose와 backend가 동일한 값을 참조) |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 관리자 콘솔(`/admin`) Basic 인증 계정 |

`backend`는 `application.yml`에서 이 값들을 환경변수로만 참조하며, 시크릿을 코드/설정 파일에 하드코딩하지 않는다.

### 2. DB 실행 (Docker Compose)

```bash
docker compose --env-file .env up -d
```

- `pgvector/pgvector:0.8.5-pg16-trixie` 이미지 사용 (PostgreSQL 16 + pgvector 확장 포함)
- 스키마는 앱 기동 시 Flyway가 `backend/src/main/resources/db/migration`의 마이그레이션을 적용해 만든다. 수동 DDL 실행은 필요 없다.
- 이후 Hibernate가 `ddl-auto: validate`로 엔티티와 실제 스키마가 어긋나지 않는지 확인한다.

### 3. 백엔드 실행

Windows:

```powershell
cd backend
.\gradlew.bat bootRun
```

macOS/Linux:

```bash
cd backend
./gradlew bootRun
```

환경 변수는 `.env`를 셸에 로드한 뒤 실행하거나, IDE 실행 설정에 등록해서 주입한다.

### 4. 빌드/테스트

```bash
cd backend
./gradlew build
```

테스트는 Testcontainers가 전용 PostgreSQL 컨테이너를 띄워서 돈다.
- 필요한 것: **Docker 데몬만** (docker-compose DB나 `.env`는 필요 없다 — 테스트는 더미 키와 컨테이너 DB를 쓴다)
- 개발용 DB(2번에서 띄운 것)와 완전히 격리되며, CI에서도 Docker만 있으면 그대로 돈다

### 5. 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
```

- dev 서버가 `/api`를 `localhost:8080`(백엔드)으로 프록시하므로 CORS 설정이 필요 없다
- 5173 포트가 사용 중이면 Vite가 다음 포트(5174 등)로 올라간다 — 터미널 출력의 주소를 확인할 것
- 테스트/빌드: `npm test` (vitest), `npm run build` (tsc + vite)

### 6. 운영 (관리자 콘솔)

`/admin`에서 (`ADMIN_USERNAME`/`ADMIN_PASSWORD`로 로그인):

1. **내한 감지** — 수집 데이터에서 감지된 KR 미래 공연을 확인하고 원클릭으로 이벤트 등록
   (수집 대상으로 등록된 아티스트의 내한만 잡힌다)
2. **아티스트 등록** — 밴드명으로 setlist.fm을 검색해 후보 중에서 선택.
   동명 프로젝트가 많으므로 disambiguation을 확인하고 본체를 고를 것
   (실측: "Megadeth" 검색의 첫 결과는 본체가 아니었다). 등록하면 수집이 자동 시작된다
3. **이벤트 등록** — 아티스트·공연일·유형(FESTIVAL/SOLO)을 넣으면 예측까지 즉시 계산
4. **배치 실행** — 수집 수동 트리거(비동기·중복 실행 방지, 완료 시 매칭·예측까지 체인)와
   적중률 매칭 + 예측 재계산. 페스티벌 직후 즉시 재수집 용도
5. **배치 이력** — collection_log 최근 30건과 진행 상태

## 디렉터리 구조

```
/backend          Spring Boot 애플리케이션 (com.encore)
  /artist         아티스트 도메인
  /setlist        공연/셋리스트 도메인 (수집 포함)
  /prediction     예측 로직
  /rag            RAG 파이프라인
  /batch          배치 실행 이력 (수집/예측/임베딩 공용)
  /pipeline       일일 파이프라인 (수집 → 매칭 → 예측) + 스케줄
  /api            REST 어댑터 (컨트롤러, 응답 DTO, Problem Detail 변환)
  /common         공통(설정, 예외, 응답 래퍼)
/frontend         React SPA
  /src/api        백엔드 클라이언트 (타입, fetch 래퍼, TanStack Query 훅)
  /src/pages      화면 (이벤트 목록 / 예측 상세 / 곡 상세)
  /src/lib        표기용 순수 함수 (vitest 단위 테스트)
/docs             설계 문서 (PRD, 스키마)
docker-compose.yml
```

## 참고

- setlist.fm 데이터를 사용합니다. 출처: https://www.setlist.fm
