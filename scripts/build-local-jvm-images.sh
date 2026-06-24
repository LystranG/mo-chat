#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export LOCAL_IMAGE_MODE=jvm
exec "$repo_root/scripts/build-local-images.sh" "$@"
