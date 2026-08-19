#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
NOTES="${2:-根据当前构建发布。}"
FORCE="${3:-false}"
HOST="${UPDATE_HOST:-baidu-bcc}"
REMOTE_DIR="/var/www/jbd-bms"

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

TMP_JSON="$(mktemp)"
python3 - "$TMP_JSON" "$VERSION_CODE" "$VERSION_NAME" "$NOTES" "$FORCE" <<'PY'
import json
import sys

path, version_code, version_name, notes, force = sys.argv[1:]
payload = {
    "versionCode": int(version_code),
    "versionName": version_name,
    "apkUrl": "http://106.13.175.227/jbd-bms/latest.apk",
    "forceUpdate": force.lower() in {"1", "true", "yes", "force"},
    "releaseNotes": notes,
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY

scp -q "$APK" "$HOST:$REMOTE_DIR/latest.apk"
scp -q "$TMP_JSON" "$HOST:$REMOTE_DIR/version.json"
rm -f "$TMP_JSON"
ssh "$HOST" "chmod 644 '$REMOTE_DIR/latest.apk' '$REMOTE_DIR/version.json'"

echo "已发布 v${VERSION_NAME} (versionCode ${VERSION_CODE}) 到 http://106.13.175.227/jbd-bms/version.json"
