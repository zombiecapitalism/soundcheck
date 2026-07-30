# 설계 산출물 (Design Docs) — Soundcheck

포트폴리오 제출용 핵심 설계 산출물 모음.

## 작성 방식에 대하여

이 산출물들은 통상의 순서(설계서 작성 → 구현)가 아니라, **AI 협업으로 프로토타입을 먼저 구현한 뒤 코드를 역설계(reverse-engineering)하여 문서화**했다. 따라서:

- **코드가 원본이다.** 문서와 코드가 불일치하면 코드를 기준으로 문서를 갱신한다.
- 각 문서에는 구현 과정에서 내린 실제 설계 결정(예: DEFERRABLE 유니크 제약, SSE delta JSON 인코딩, 예측 스냅샷 고정)과 그 이유가 담겨 있다 — 사후 문서화이기에 오히려 "왜 이렇게 됐는가"의 근거가 구체적이다.
- 역설계 과정에서 발견된 미비점(404 화면 부재 등)도 숨기지 않고 개선 항목으로 기록했다.

## 문서 목록

| 문서 | 내용 |
|------|------|
| [테이블설계서](table-spec.md) | ERD, 테이블 10종 컬럼 정의·제약·인덱스, 마이그레이션 이력(V1~V7), 곡명 정규화 규칙 |
| [프로세스 정의서](process-spec.md) | 프로세스 8종 — 일일 파이프라인, 수집, 예측, RAG 수집·생성, 적중률 평가. 흐름도와 오류 격리·외부 API 정책 |
| [인터페이스 정의서](api-spec.md) | REST API 16종 명세, SSE 계약, RFC 7807 에러 규약, 설계 결정 기록 |
| [화면설계서](screen-spec.md) | 화면 4종 와이어프레임, 화면 흐름도, 상태 처리 매트릭스, 개선 항목 |

## 상위 문서

| 문서 | 내용 |
|------|------|
| [/docs/setlist-prd.md](../setlist-prd.md) | 제품 요구사항 (v0.2 — 확장 기능 E1~E11 포함) |
| [/docs/setlist-schema.md](../setlist-schema.md) | 데이터 설계 원본 + setlist.fm API 실측 검증 기록 |
| [/docs/implementation-plan.md](../implementation-plan.md) | 확장 기능 구현 순서 (P1~P3) |
| [/docs/tuning-log.md](../tuning-log.md) | 예측 파라미터 튜닝 기록 |
