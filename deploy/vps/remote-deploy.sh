#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:?APP_DIR is required}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.vps.yml}"
ENV_FILE="${ENV_FILE:-.env}"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-.env.deploy}"
BACKEND_IMAGE="${BACKEND_IMAGE:?BACKEND_IMAGE is required}"
FRONTEND_IMAGE="${FRONTEND_IMAGE:?FRONTEND_IMAGE is required}"
GHCR_USERNAME="${GHCR_USERNAME:?GHCR_USERNAME is required}"
GHCR_TOKEN="${GHCR_TOKEN:?GHCR_TOKEN is required}"

mkdir -p "${APP_DIR}"
cd "${APP_DIR}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${APP_DIR}/${ENV_FILE}. Create it from .env.example before the first deployment." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required on the VPS host." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "docker compose (v2 plugin) is required on the VPS host." >&2
  exit 1
fi

cat > "${DEPLOY_ENV_FILE}" <<EOF
BACKEND_IMAGE=${BACKEND_IMAGE}
FRONTEND_IMAGE=${FRONTEND_IMAGE}
EOF

chmod 600 "${DEPLOY_ENV_FILE}"

echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USERNAME}" --password-stdin

docker compose \
  --env-file "${ENV_FILE}" \
  --env-file "${DEPLOY_ENV_FILE}" \
  -f "${COMPOSE_FILE}" \
  pull

docker compose \
  --env-file "${ENV_FILE}" \
  --env-file "${DEPLOY_ENV_FILE}" \
  -f "${COMPOSE_FILE}" \
  up -d --remove-orphans

docker image prune -f

docker compose \
  --env-file "${ENV_FILE}" \
  --env-file "${DEPLOY_ENV_FILE}" \
  -f "${COMPOSE_FILE}" \
  ps
