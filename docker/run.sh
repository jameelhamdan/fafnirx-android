#!/bin/sh
# Runs inside the container: ./docker/run.sh test|build|keygen|shell
set -eu
cd /src
VERSION="$(cat VERSION)"

fix_owner() {
  if [ -n "${HOST_UID:-}" ] && [ "$HOST_UID" != 0 ]; then
    chown -R "$HOST_UID:${HOST_GID:-$HOST_UID}" /src/build /src/app/build /src/.gradle /src/.signing /src/dist 2>/dev/null || true
  fi
}
trap fix_owner EXIT

case "${1:-build}" in
  test)
    gradle --no-daemon --console=plain :app:testDebugUnitTest
    ;;
  build)
    gradle --no-daemon --console=plain :app:testDebugUnitTest :app:assembleRelease
    sh ./docker/sign.sh "app/build/outputs/apk/release/app-release-unsigned.apk" "dist/fafnirx-android-${VERSION}.apk"
    ;;
  keygen)
    sh ./docker/sign.sh --keygen
    ;;
  shell)
    exec sh
    ;;
  *)
    echo "usage: run.sh test|build|keygen|shell" >&2
    exit 2
    ;;
esac
