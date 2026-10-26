#!/bin/sh
# Builds with Docker only. ./build.sh [build|test|keygen|shell]
set -eu
cd "$(dirname "$0")"
ROOT="$(pwd)"
IMAGE=fafnirx-android-build
docker build --platform linux/amd64 -t "$IMAGE" docker/
exec docker run --rm --platform linux/amd64 \
  -v "$ROOT":/src -v fafnirx-gradle:/gradle-home \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -e KEYSTORE -e KEYSTORE_PASSWORD -e KEY_ALIAS -e MAX_APK_BYTES \
  "$IMAGE" sh /src/docker/run.sh "${1:-build}"
