#!/usr/bin/env bash
set -euo pipefail
# Run in a fresh checkout with sibling dependency directories. No destructive cleanup.
ROOT="$PWD"
source buildScript/lib/core/get_source_env.sh
test ! -e ../sing-box
test ! -e ../libneko
git clone --quiet "$REPOSITORY_SING_BOX" ../sing-box
git -C ../sing-box switch --detach "$COMMIT_SING_BOX"
for patch in "$ROOT"/buildScript/lib/core/patches/*.patch; do
  git -C ../sing-box apply --check "$patch"
  git -C ../sing-box apply "$patch"
done
(cd ../sing-box && go test ./boxapi -tags=with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0 -ldflags=-checklinkname=0)
git clone --quiet "$REPOSITORY_LIBNEKO" ../libneko
git -C ../libneko switch --detach "$COMMIT_LIBNEKO"
git clone --quiet "$REPOSITORY_GOMOBILE" libcore/gomobile
git -C libcore/gomobile switch --detach "$COMMIT_GOMOBILE"
cd libcore/gomobile
go install ./cmd/gomobile ./cmd/gobind
cd "$ROOT/libcore"
go mod tidy
go test ./... -tags=with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0 -ldflags=-checklinkname=0
gomobile init
mkdir -p .dynamic-build
GOBIND="$(go env GOPATH)/bin/gobind" gomobile bind -v -target=android/arm64,android/amd64 -androidapi 23 \
  -cache "$PWD/.dynamic-build" -trimpath -ldflags='-s -w -checklinkname=0' \
  -tags='with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0' .
