#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
NOTES="${2:-根据当前构建发布。}"
FORCE="${3:-false}"
HOST="${UPDATE_HOST:-baidu-bcc}"
REMOTE_DIR="/var/www/jbd-bms"
REPO="${GITHUB_REPO:-ShiShanLing/JBD-BMS-Manager}"

if [[ ! -f "$APK" ]]; then
  echo "找不到安装包：$APK" >&2
  echo "请先构建：./gradlew assembleDebug" >&2
  exit 1
fi

VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)"
VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)"

if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
  echo "无法从 app/build.gradle.kts 读取版本号" >&2
  exit 1
fi

TAG="v${VERSION_NAME}"
ASSET_NAME="JBD-BMS-Manager-v${VERSION_NAME}.apk"
APK_URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET_NAME}"
TITLE="电动BMS v${VERSION_NAME}"

STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT
cp "$APK" "$STAGING/$ASSET_NAME"

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$STAGING/$ASSET_NAME" --repo "$REPO" --clobber
  gh release edit "$TAG" --repo "$REPO" --title "$TITLE" --notes "$NOTES"
else
  gh release create "$TAG" "$STAGING/$ASSET_NAME" \
    --repo "$REPO" \
    --title "$TITLE" \
    --notes "$NOTES"
fi

TMP_JSON="$(mktemp)"
python3 - "$TMP_JSON" "$VERSION_CODE" "$VERSION_NAME" "$NOTES" "$FORCE" "$APK_URL" <<'PY'
import json
import sys

path, version_code, version_name, notes, force, apk_url = sys.argv[1:]
payload = {
    "versionCode": int(version_code),
    "versionName": version_name,
    "apkUrl": apk_url,
    "forceUpdate": force.lower() in {"1", "true", "yes", "force"},
    "releaseNotes": notes,
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY

scp -q "$TMP_JSON" "$HOST:$REMOTE_DIR/version.json"
rm -f "$TMP_JSON"
ssh "$HOST" "chmod 644 '$REMOTE_DIR/version.json'"

echo "已发布 v${VERSION_NAME} (versionCode ${VERSION_CODE})"
echo "  GitHub: ${APK_URL}"
echo "  检查更新: http://106.13.175.227/jbd-bms/version.json"
