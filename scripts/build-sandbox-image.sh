#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
IMAGE_TAG="${AGENT_SANDBOX_DOCKER_IMAGE:-jade-sandbox:1}"

echo "Building Jade Docker sandbox image: ${IMAGE_TAG}"
docker build -t "${IMAGE_TAG}" -f "${PROJECT_ROOT}/sandbox/Dockerfile" "${PROJECT_ROOT}/sandbox"
DIGEST="$(docker image inspect --format '{{index .RepoDigests 0}}' "${IMAGE_TAG}" 2>/dev/null || docker image inspect --format '{{.Id}}' "${IMAGE_TAG}")"
echo "Built ${IMAGE_TAG} (${DIGEST})"
