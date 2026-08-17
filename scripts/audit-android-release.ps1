[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$BundlePath,
    [int]$ExpectedVersionCode = 100000,
    [string]$ExpectedVersionName = "1.0.0",
    [string]$ExpectedTargetRuleKeyId,
    [switch]$RequireSigned,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$workspaceRoot = Split-Path -Parent $PSScriptRoot

function Resolve-ArtifactPath {
    param(
        [string]$RequestedPath,
        [string[]]$DefaultCandidates,
        [string]$Label
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        $resolved = Resolve-Path -LiteralPath $RequestedPath -ErrorAction Stop
        return $resolved.Path
    }

    foreach ($candidate in $DefaultCandidates) {
        $fullPath = Join-Path $workspaceRoot $candidate
        if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            return (Resolve-Path -LiteralPath $fullPath).Path
        }
    }

    throw "找不到 $Label。请先构建产物，或通过参数提供完整路径。"
}

function Find-AndroidBuildTool {
    param([string]$FileName)

    $sdkRoot = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $sdkRoot = $env:ANDROID_HOME
    }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        throw "未设置 ANDROID_SDK_ROOT 或 ANDROID_HOME。请先运行 scripts/dev-env.ps1。"
    }

    $buildToolsRoot = Join-Path $sdkRoot "build-tools"
    $candidate = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName $FileName } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $candidate) {
        throw "Android SDK 中找不到 $FileName。"
    }
    return $candidate
}

function Invoke-CheckedTool {
    param(
        [string]$ToolPath,
        [string[]]$Arguments,
        [string]$Label
    )

    $output = & $ToolPath @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "$Label 失败。`n$output"
    }
    return $output
}

function Test-ZipJarSignature {
    param([string]$Path)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return @($archive.Entries | Where-Object {
            $_.FullName -match '^META-INF/[^/]+\.(RSA|DSA|EC)$'
        }).Count -gt 0
    } finally {
        $archive.Dispose()
    }
}

function Find-ZipAsciiMarker {
    param(
        [string]$Path,
        [string[]]$Markers,
        [string]$EntryPattern
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($entry in $archive.Entries | Where-Object { $_.FullName -like $EntryPattern }) {
            $stream = $entry.Open()
            $memory = [System.IO.MemoryStream]::new()
            try {
                $stream.CopyTo($memory)
                $text = [System.Text.Encoding]::ASCII.GetString($memory.ToArray())
                foreach ($marker in $Markers) {
                    if ($text.Contains($marker)) {
                        return $marker
                    }
                }
            } finally {
                $memory.Dispose()
                $stream.Dispose()
            }
        }
        return $null
    } finally {
        $archive.Dispose()
    }
}

$resolvedApk = Resolve-ArtifactPath -RequestedPath $ApkPath -Label "Release APK" -DefaultCandidates @(
    "composeApp\build\outputs\apk\release\composeApp-release.apk",
    "composeApp\build\outputs\apk\release\composeApp-release-unsigned.apk"
)
$resolvedBundle = Resolve-ArtifactPath -RequestedPath $BundlePath -Label "Release AAB" -DefaultCandidates @(
    "composeApp\build\outputs\bundle\release\composeApp-release.aab"
)

$aapt2 = Find-AndroidBuildTool "aapt2.exe"
$apksigner = Find-AndroidBuildTool "apksigner.bat"
$failures = [System.Collections.Generic.List[string]]::new()

$permissionOutput = Invoke-CheckedTool -ToolPath $aapt2 -Label "读取 APK 权限" -Arguments @(
    "dump", "permissions", $resolvedApk
)
$usesPermissions = @(
    $permissionOutput -split "`r?`n" |
        ForEach-Object {
            if ($_ -match "^uses-permission: name='([^']+)'") { $Matches[1] }
        } |
        Where-Object { $_ } |
        Sort-Object -Unique
)
$allowedPermissions = @(
    "android.permission.INTERNET",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "io.github.wintopic.teambox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    "io.github.wintopic.teambox.permission.CONTROL_SERVICE"
)
$unexpectedPermissions = @($usesPermissions | Where-Object { $_ -notin $allowedPermissions })
if ($unexpectedPermissions.Count -gt 0) {
    $failures.Add("发现未列入白名单的权限：$($unexpectedPermissions -join ', ')")
}
if ("android.permission.INTERNET" -notin $usesPermissions) {
    $failures.Add("Release 必须申请 android.permission.INTERNET 以检查更新")
}
foreach ($forbiddenPermission in @("android.permission.ACCESS_NETWORK_STATE")) {
    if ($forbiddenPermission -in $usesPermissions) {
        $failures.Add("Release 不得申请 $forbiddenPermission")
    }
}

$manifestOutput = Invoke-CheckedTool -ToolPath $aapt2 -Label "读取 APK Manifest" -Arguments @(
    "dump", "xmltree", "--file", "AndroidManifest.xml", $resolvedApk
)
$requiredManifestPatterns = [ordered]@{
    "versionCode" = "versionCode.*=$ExpectedVersionCode"
    "versionName" = 'versionName.*="' + [regex]::Escape($ExpectedVersionName) + '"'
    "allowBackup=false" = "allowBackup.*=false"
    "usesCleartextTraffic=false" = "usesCleartextTraffic.*=false"
}
foreach ($entry in $requiredManifestPatterns.GetEnumerator()) {
    if ($manifestOutput -notmatch $entry.Value) {
        $failures.Add("Manifest 缺少或不符合 $($entry.Key)")
    }
}
foreach ($forbiddenPattern in @(
    "debuggable.*=true",
    "DebugCommandReceiver",
    "com\.danmukey\.debug\.action"
)) {
    if ($manifestOutput -match $forbiddenPattern) {
        $failures.Add("Manifest 命中禁止项 $forbiddenPattern")
    }
}

$developmentTrustMarkers = @(
    "danmukey-development-2026-08",
    "3059301306072a8648ce3d020106082a8648ce3d030107"
)
$apkDevelopmentTrustMarker = Find-ZipAsciiMarker `
    -Path $resolvedApk `
    -EntryPattern "classes*.dex" `
    -Markers $developmentTrustMarkers
$bundleDevelopmentTrustMarker = Find-ZipAsciiMarker `
    -Path $resolvedBundle `
    -EntryPattern "base/dex/classes*.dex" `
    -Markers $developmentTrustMarkers
if ($apkDevelopmentTrustMarker) {
    $failures.Add("Release APK 包含开发目标规则信任材料：$apkDevelopmentTrustMarker")
}
if ($bundleDevelopmentTrustMarker) {
    $failures.Add("Release AAB 包含开发目标规则信任材料：$bundleDevelopmentTrustMarker")
}

$apkExpectedTargetRuleKey = $null
$bundleExpectedTargetRuleKey = $null
if (-not [string]::IsNullOrWhiteSpace($ExpectedTargetRuleKeyId)) {
    $apkExpectedTargetRuleKey = Find-ZipAsciiMarker `
        -Path $resolvedApk `
        -EntryPattern "classes*.dex" `
        -Markers @($ExpectedTargetRuleKeyId)
    $bundleExpectedTargetRuleKey = Find-ZipAsciiMarker `
        -Path $resolvedBundle `
        -EntryPattern "base/dex/classes*.dex" `
        -Markers @($ExpectedTargetRuleKeyId)
    if (-not $apkExpectedTargetRuleKey) {
        $failures.Add("Release APK 不包含预期目标规则 keyId：$ExpectedTargetRuleKeyId")
    }
    if (-not $bundleExpectedTargetRuleKey) {
        $failures.Add("Release AAB 不包含预期目标规则 keyId：$ExpectedTargetRuleKeyId")
    }
}

$apkSignatureOutput = & $apksigner verify --verbose --print-certs $resolvedApk 2>&1 | Out-String
$apkSigned = $LASTEXITCODE -eq 0
$bundleSigned = Test-ZipJarSignature $resolvedBundle
if ($RequireSigned -and -not $apkSigned) {
    $failures.Add("APK 未签名或签名校验失败")
}
if ($RequireSigned -and -not $bundleSigned) {
    $failures.Add("AAB 未签名")
}

$apkFile = Get-Item -LiteralPath $resolvedApk
$bundleFile = Get-Item -LiteralPath $resolvedBundle
$report = [ordered]@{
    auditedAt = (Get-Date).ToString("o")
    expectedVersionCode = $ExpectedVersionCode
    expectedVersionName = $ExpectedVersionName
    expectedTargetRuleKeyId = $ExpectedTargetRuleKeyId
    requireSigned = [bool]$RequireSigned
    permissions = $usesPermissions
    developmentTargetRuleTrustPresent = [ordered]@{
        apk = [bool]$apkDevelopmentTrustMarker
        bundle = [bool]$bundleDevelopmentTrustMarker
    }
    expectedTargetRuleTrustPresent = [ordered]@{
        apk = [bool]$apkExpectedTargetRuleKey
        bundle = [bool]$bundleExpectedTargetRuleKey
    }
    apk = [ordered]@{
        path = $apkFile.FullName
        size = $apkFile.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkFile.FullName).Hash
        signed = $apkSigned
        signatureOutput = $apkSignatureOutput.Trim()
    }
    bundle = [ordered]@{
        path = $bundleFile.FullName
        size = $bundleFile.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $bundleFile.FullName).Hash
        signed = $bundleSigned
    }
    policyPassed = $failures.Count -eq 0
    failures = @($failures)
}

$report | ConvertTo-Json -Depth 6

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $outputFullPath = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
        $OutputPath
    } else {
        Join-Path $workspaceRoot $OutputPath
    }
    $outputDirectory = Split-Path -Parent $outputFullPath
    if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outputFullPath -Encoding UTF8
}

if ($failures.Count -gt 0) {
    throw ($failures -join "`n")
}
