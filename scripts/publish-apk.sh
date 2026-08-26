#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
FORCE="${3:-false}"
HOST="${UPDATE_HOST:-baidu-bcc}"
REMOTE_DIR="/var/www/jbd-bms"
REPO="${GITHUB_REPO:-ShiShanLing/JBD-BMS-Manager}"
SERVER_APK_NAME="latest.apk"
SERVER_APK_URL="${SERVER_APK_URL:-http://106.13.175.227/jbd-bms/$SERVER_APK_NAME}"

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

CHANGELOG_NOTES="$(awk -v version="$VERSION_NAME" '
  index($0, "## [" version "]") == 1 { capture = 1; next }
  capture && /^## \[/ { exit }
  capture { print }
' "$ROOT/CHANGELOG.md")"
if ! grep -q '[^[:space:]]' <<<"$CHANGELOG_NOTES"; then
  echo "CHANGELOG.md 中缺少版本 $VERSION_NAME 的详细更新记录" >&2
  exit 1
fi
NOTES="${2:-$CHANGELOG_NOTES}"

TAG="v${VERSION_NAME}"
ASSET_NAME="JBD-BMS-Manager-v${VERSION_NAME}.apk"
GITHUB_APK_URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET_NAME}"
APK_URL="${PUBLIC_APK_URL:-$SERVER_APK_URL}"
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

# 服务器始终只保留一个 latest.apk。先完整上传临时文件，再原子替换旧包。
scp -q "$APK" "$HOST:$REMOTE_DIR/$SERVER_APK_NAME.uploading"
ssh "$HOST" "chmod 644 '$REMOTE_DIR/$SERVER_APK_NAME.uploading' && mv -f '$REMOTE_DIR/$SERVER_APK_NAME.uploading' '$REMOTE_DIR/$SERVER_APK_NAME'"

TMP_JSON="$(mktemp)"
VERSION_URL="${VERSION_URL:-http://106.13.175.227/jbd-bms/version.json}"
python3 - "$TMP_JSON" "$VERSION_CODE" "$VERSION_NAME" "$NOTES" "$FORCE" "$APK_URL" "$VERSION_URL" <<'PY'
import json
import sys
import urllib.request

path, version_code, version_name, notes, force, apk_url, version_url = sys.argv[1:]
new_code = int(version_code)
payload = {
    "versionCode": new_code,
    "versionName": version_name,
    "apkUrl": apk_url,
    "forceUpdate": force.lower() in {"1", "true", "yes", "force"},
    "releaseNotes": notes,
}

known = {
    33: ("0.5.3", "更新弹窗会列出跳过的中间版本说明，不再只显示最后一次更新内容。"),
    32: ("0.5.2", "应用名称统一为「电动 BMS」，桌面图标与 App 内标题一致。"),
    31: ("0.5.1", "应用名称改为电动BMS，桌面图标用短名，App 内标题为「电动 BMS」。"),
    30: (
        "0.5.0",
        "新增画中画小窗：连上 BMS 后按 Home 或点详情页右下角按钮进入，骑行/充电自动切换，关闭小窗回到后台。\n"
        "新增保护参数只读页。\n"
        "充电判断改为静置且电流大于 7A，避免把动能回收当成插枪充电。",
    ),
}

changelog = []
seen = {new_code}
old = None
try:
    with urllib.request.urlopen(version_url, timeout=15) as response:
        old = json.load(response)
except Exception:
    old = None

if isinstance(old, dict):
    previous_code = int(old.get("versionCode") or 0)
    if previous_code > 0 and previous_code not in seen:
        changelog.append({
            "versionCode": previous_code,
            "versionName": str(old.get("versionName") or ""),
            "releaseNotes": str(old.get("releaseNotes") or ""),
        })
        seen.add(previous_code)
    for item in old.get("changelog") or []:
        if not isinstance(item, dict):
            continue
        code = int(item.get("versionCode") or 0)
        if code <= 0 or code in seen:
            continue
        changelog.append({
            "versionCode": code,
            "versionName": str(item.get("versionName") or ""),
            "releaseNotes": str(item.get("releaseNotes") or ""),
        })
        seen.add(code)

for code, (name, text) in sorted(known.items(), reverse=True):
    if code < new_code and code not in seen:
        changelog.append({
            "versionCode": code,
            "versionName": name,
            "releaseNotes": text,
        })
        seen.add(code)

changelog.sort(key=lambda item: item["versionCode"], reverse=True)
payload["changelog"] = changelog[:20]

with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY

scp -q "$TMP_JSON" "$HOST:$REMOTE_DIR/version.json"
rm -f "$TMP_JSON"
ssh "$HOST" "chmod 644 '$REMOTE_DIR/version.json'"

echo "已发布 v${VERSION_NAME} (versionCode ${VERSION_CODE})"
echo "  GitHub: ${GITHUB_APK_URL}"
echo "  服务器: ${APK_URL}"
echo "  检查更新: http://106.13.175.227/jbd-bms/version.json"
