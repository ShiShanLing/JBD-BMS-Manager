param(
    [string]$ApkPath = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Notes = "",
    [switch]$Force,
    [string]$HostAlias = $(if ($env:UPDATE_HOST) { $env:UPDATE_HOST } else { "baidu-bcc" }),
    [string]$RemoteDir = '/var/www/jbd-bms',
    [string]$Repo = $(if ($env:GITHUB_REPO) { $env:GITHUB_REPO } else { "ShiShanLing/JBD-BMS-Manager" }),
    [string]$VersionUrl = $(if ($env:VERSION_URL) { $env:VERSION_URL } else { "https://shishanling.cn/jbd-bms/version.json" }),
    [int]$MinimumUpdatableVersionCode = $(if ($env:MIN_UPDATABLE_VERSION_CODE) { [int]$env:MIN_UPDATABLE_VERSION_CODE } else { 30 }),
    [string]$MinimumUpdatableVersionName = $(if ($env:MIN_UPDATABLE_VERSION_NAME) { $env:MIN_UPDATABLE_VERSION_NAME } else { "0.5.0" })
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path "$PSScriptRoot\..").Path
$GradleFile = Join-Path $Root "app\build.gradle.kts"

if (-not (Test-Path $ApkPath)) {
    Write-Error "找不到安装包：$ApkPath`n请先构建：.\gradlew.bat assembleDebug"
}

$content = Get-Content $GradleFile -Raw
if ($content -match 'versionCode\s*=\s*(\d+)') { $VersionCode = [int]$Matches[1] } else { throw "无法读取 versionCode" }
if ($content -match 'versionName\s*=\s*"([^"]+)"') { $VersionName = $Matches[1] } else { throw '无法读取 versionName' }

$ChangelogPath = Join-Path $Root "CHANGELOG.md"
if (-not (Test-Path $ChangelogPath)) { throw "找不到 CHANGELOG.md" }
$CollectNotes = $false
$ChangelogLines = New-Object System.Collections.Generic.List[string]
foreach ($Line in (Get-Content $ChangelogPath)) {
    if ($Line.StartsWith("## [$VersionName]")) {
        $CollectNotes = $true
        continue
    }
    if ($CollectNotes -and $Line.StartsWith("## [")) { break }
    if ($CollectNotes) { $ChangelogLines.Add($Line) }
}
$ChangelogNotes = (($ChangelogLines -join "`n").Trim())
if ([string]::IsNullOrWhiteSpace($ChangelogNotes)) {
    throw "CHANGELOG.md 中缺少版本 $VersionName 的详细更新记录"
}
if ([string]::IsNullOrWhiteSpace($Notes)) { $Notes = $ChangelogNotes }

$Tag = "v$VersionName"
$AssetName = "JBD-BMS-Manager-v$VersionName.apk"
$GitHubApkUrl = "https://github.com/$Repo/releases/download/$Tag/$AssetName"
$ApkUrl = if ($env:PUBLIC_APK_URL) { $env:PUBLIC_APK_URL } else { $GitHubApkUrl }
$Title = "电动BMS v$VersionName"
$Staging = Join-Path $env:TEMP ("jbd-apk-" + [guid]::NewGuid().ToString("n"))
New-Item -ItemType Directory -Path $Staging | Out-Null
$StagedApk = Join-Path $Staging $AssetName
Copy-Item $ApkPath $StagedApk

try {
    gh release view $Tag --repo $Repo 2>$null
    if ($LASTEXITCODE -eq 0) {
        gh release upload $Tag $StagedApk --repo $Repo --clobber
        gh release edit $Tag --repo $Repo --title $Title --notes $Notes
    } else {
        gh release create $Tag $StagedApk --repo $Repo --title $Title --notes $Notes
    }
    if ($LASTEXITCODE -ne 0) { throw "GitHub Release 上传失败，请先执行 gh auth login" }

    $known = @{
        35 = @("0.5.5", "新增里程 Tab：日历与柱状图统计日/周/月/年骑行里程；概览与画中画显示今日总里程；优化骑行 PiP 布局。")
        33 = @("0.5.3", "更新弹窗会列出跳过的中间版本说明，不再只显示最后一次更新内容。")
        32 = @("0.5.2", "应用名称统一为「电动 BMS」，桌面图标与 App 内标题一致。")
        31 = @("0.5.1", "应用名称改为电动BMS，桌面图标用短名，App 内标题为「电动 BMS」。")
        30 = @(
            "0.5.0",
            "新增画中画小窗：连上 BMS 后按 Home 或点详情页右下角按钮进入，骑行/充电自动切换，关闭小窗回到后台。`n新增保护参数只读页。`n充电判断改为静置且电流大于 7A，避免把动能回收当成插枪充电。"
        )
    }

    $old = $null
    try {
        $old = Invoke-RestMethod -Uri $VersionUrl -TimeoutSec 15
    } catch {
        $old = $null
    }

    $changelog = New-Object System.Collections.Generic.List[object]
    $seen = New-Object 'System.Collections.Generic.HashSet[int]'
    [void]$seen.Add($VersionCode)

    if ($old) {
        $previousCode = if ($null -ne $old.versionCode) { [int]$old.versionCode } else { 0 }
        if ($previousCode -gt 0 -and -not $seen.Contains($previousCode)) {
            $changelog.Add([ordered]@{
                versionCode = $previousCode
                versionName = [string]$old.versionName
                releaseNotes = [string]$old.releaseNotes
            }) | Out-Null
            [void]$seen.Add($previousCode)
        }
        $oldChangelog = if ($null -ne $old.changelog) { $old.changelog } else { @() }
        foreach ($item in $oldChangelog) {
            $code = if ($null -ne $item.versionCode) { [int]$item.versionCode } else { 0 }
            if ($code -le 0 -or $seen.Contains($code)) { continue }
            $changelog.Add([ordered]@{
                versionCode = $code
                versionName = [string]$item.versionName
                releaseNotes = [string]$item.releaseNotes
            }) | Out-Null
            [void]$seen.Add($code)
        }
    }

    foreach ($entry in ($known.GetEnumerator() | Sort-Object Name -Descending)) {
        $code = [int]$entry.Key
        if ($code -lt $VersionCode -and -not $seen.Contains($code)) {
            $changelog.Add([ordered]@{
                versionCode = $code
                versionName = $entry.Value[0]
                releaseNotes = $entry.Value[1]
            }) | Out-Null
            [void]$seen.Add($code)
        }
    }

    $payload = [ordered]@{
        versionCode = $VersionCode
        versionName = $VersionName
        apkUrl = $ApkUrl
        forceUpdate = [bool]$Force
        minimumUpdatableVersionCode = $MinimumUpdatableVersionCode
        minimumUpdatableVersionName = $MinimumUpdatableVersionName
        releaseNotes = $Notes
        changelog = @($changelog | Sort-Object versionCode -Descending | Select-Object -First 20)
    }

    $TmpJson = Join-Path $env:TEMP ("version-" + [guid]::NewGuid().ToString("n") + ".json")
    $payload | ConvertTo-Json -Depth 6 | Set-Content -Path $TmpJson -Encoding UTF8
    scp -q $TmpJson "${HostAlias}:${RemoteDir}/version.json"
    ssh $HostAlias "chmod 644 '$RemoteDir/version.json'"
    Remove-Item $TmpJson -Force

    Write-Host "已发布 v$VersionName (versionCode $VersionCode)"
    Write-Host "  GitHub: $GitHubApkUrl"
    Write-Host "  应用内下载: $ApkUrl"
    Write-Host "  检查更新: $VersionUrl"
} finally {
    Remove-Item $Staging -Recurse -Force -ErrorAction SilentlyContinue
}
