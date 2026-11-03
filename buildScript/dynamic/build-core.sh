#!/usr/bin/env bash
set -euo pipefail
# Run in a fresh checkout with sibling dependency directories. No destructive cleanup.
ROOT="$PWD"
source buildScript/lib/core/get_source_env.sh
CORE_COMMIT=${CORE_COMMIT:-$COMMIT_SING_BOX}
CORE_VERSION=${CORE_VERSION:-1.14.4-dynamic.1}
[[ "$CORE_COMMIT" =~ ^[a-f0-9]{40}$ ]]
[[ "$CORE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-dynamic\.1$ ]]
test ! -e ../sing-box
test ! -e ../libneko
git clone --quiet "$REPOSITORY_SING_BOX" ../sing-box
git -C ../sing-box switch --detach "$CORE_COMMIT"
[[ "$(git -C ../sing-box rev-parse "v${CORE_VERSION%-dynamic.1}^{commit}")" == "$CORE_COMMIT" ]]
for patch in "$ROOT"/buildScript/lib/core/patches/*.patch; do
  # The version is injected by the linker, so this historical fixed-version patch is not used.
  [[ "$(basename "$patch")" == 0005-* ]] && continue
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
  -cache "$PWD/.dynamic-build" -trimpath -ldflags="-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=$CORE_VERSION" \
  -tags='with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0' .
