#!/bin/sh
# Refreshes the copy of the device contract from the server repo: ./scripts/sync-proto.sh [path to the replicator repo]
set -eu
SRC="${1:-../replicator}/proto/fafnirx.proto"
cp "$SRC" "$(dirname "$0")/../app/src/main/proto/fafnirx.proto"
echo "copied $SRC"
