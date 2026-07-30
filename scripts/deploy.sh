#!/usr/bin/env sh
# 단일 서버 배포 — 이미지 재빌드 후 재기동(무중단 아님, 포트폴리오 규모 전제).
# 전제: 서버에 docker + compose 플러그인, 저장소 클론, 시크릿이 채워진 .env
set -eu
cd "$(dirname "$0")/.."

# 최신 코드로 갱신 — 로컬 수정이 있으면 실패한다(서버에서 직접 고치지 말 것)
git pull --ff-only

docker compose --env-file .env -f docker-compose.prod.yml build
docker compose --env-file .env -f docker-compose.prod.yml up -d
# 이전 버전의 dangling 이미지 정리
docker image prune -f
docker compose --env-file .env -f docker-compose.prod.yml ps
