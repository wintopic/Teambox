[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$Install,
    [switch]$RequestProjection,
    [switch]$RunTemplateCancellation,
    [switch]$RequireReady,
    [ValidateRange(1, 60)]
    [int]$ProjectionWaitSeconds = 20,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$appPackage = "io.github.wintopic.teambox.debug"
$testHostPackage = "com.danmukey.testhost"
$debugReceiver = "$appPackage/com.danmukey.app.DebugCommandReceiver"
$templateActivity = "$appPackage/com.danmukey.app.TemplateCaptureActivity"
$mainActivity = "$appPackage/com.danmukey.app.MainActivity"
$testHostActivity = "$testHostPackage/.MainActivity"
$script:SelectedSerial = $null

if ([string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    . (Join-Path $PSScriptRoot "dev-env.ps1")
}

$adbPath = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    throw "找不到 adb.exe：$adbPath"
}

function Invoke-AdbCommand {
    param(
        [string[]]$Arguments,
        [string]$Label,
        [switch]$WithoutSerial,
        [switch]$AllowFailure
    )

    $effectiveArguments = @()
    if (-not $WithoutSerial -and -not [string]::IsNullOrWhiteSpace($script:SelectedSerial)) {
        $effectiveArguments += @("-s", $script:SelectedSerial)
    }
    $effectiveArguments += $Arguments

    $rawOutput = & $adbPath @effectiveArguments 2>&1
    $exitCode = $LASTEXITCODE
    $output = ($rawOutput | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "$Label 失败（exit=$exitCode）。`n$output"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output.Trim()
    }
}

function Get-ConnectedSerials {
    $output = (Invoke-AdbCommand -Arguments @("devices") -Label "读取 ADB 设备" -WithoutSerial).Output
    return @(
        $output -split "`r?`n" |
            ForEach-Object {
                if ($_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
            } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
}

function Test-PackageInstalled {
    param([string]$PackageName)
    $result = Invoke-AdbCommand -Arguments @("shell", "pm", "path", $PackageName) -Label "查询 $PackageName" -AllowFailure
    return $result.ExitCode -eq 0 -and $result.Output -match '^package:'
}

function Get-MediaProjectionState {
    $output = (Invoke-AdbCommand -Arguments @("shell", "dumpsys", "media_projection") -Label "读取 MediaProjection 状态").Output
    return [pscustomobject]@{
        Active = $output -match "\($([regex]::Escape($appPackage)), uid=\d+\): TYPE_SCREEN_CAPTURE"
        Raw = $output
    }
}

function Get-ResumedActivity {
    $output = (Invoke-AdbCommand -Arguments @("shell", "dumpsys", "activity", "activities") -Label "读取前台 Activity").Output
    $line = $output -split "`r?`n" | Where-Object { $_ -match 'mResumedActivity:' } | Select-Object -First 1
    if ($null -eq $line) {
        return ""
    }
    return $line.Trim()
}

function Get-TemplateListing {
    $result = Invoke-AdbCommand -Arguments @(
        "shell", "run-as", $appPackage, "ls", "-ln", "files/target-templates"
    ) -Label "读取私有模板目录" -AllowFailure
    if ($result.ExitCode -eq 0) {
        return $result.Output
    }
    if ($result.Output -match 'No such file or directory') {
        return ""
    }
    throw "读取私有模板目录失败（exit=$($result.ExitCode)）。`n$($result.Output)"
}

$connectedSerials = Get-ConnectedSerials
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    if ($Serial -notin $connectedSerials) {
        throw "设备 $Serial 未连接或尚未授权。当前设备：$($connectedSerials -join ', ')"
    }
    $script:SelectedSerial = $Serial
} elseif ($connectedSerials.Count -eq 1) {
    $script:SelectedSerial = $connectedSerials[0]
} elseif ($connectedSerials.Count -eq 0) {
    throw "没有可用的 ADB 设备。"
} else {
    throw "检测到多个 ADB 设备，请通过 -Serial 指定：$($connectedSerials -join ', ')"
}

$debugApkPath = Join-Path $workspaceRoot "composeApp\build\outputs\apk\debug\composeApp-debug.apk"
$testHostApkPath = Join-Path $workspaceRoot "testHost\build\outputs\apk\debug\testHost-debug.apk"

if ($Install) {
    foreach ($artifact in @(
        [pscustomobject]@{ Path = $debugApkPath; Label = "怪团建 Debug APK" },
        [pscustomobject]@{ Path = $testHostApkPath; Label = "测试宿主 APK" }
    )) {
        if (-not (Test-Path -LiteralPath $artifact.Path -PathType Leaf)) {
            throw "找不到 $($artifact.Label)：$($artifact.Path)。请先构建。"
        }
        Write-Host "安装 $($artifact.Label)..."
        Invoke-AdbCommand -Arguments @("install", "-r", $artifact.Path) -Label "安装 $($artifact.Label)" | Out-Null
    }
}

$appInstalled = Test-PackageInstalled $appPackage
$testHostInstalled = Test-PackageInstalled $testHostPackage
if (-not $appInstalled) {
    throw "设备未安装 $appPackage。可使用 -Install。"
}

$model = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.product.model") -Label "读取设备型号").Output
$androidVersion = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.build.version.release") -Label "读取 Android 版本").Output
$apiLevel = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.build.version.sdk") -Label "读取 API 级别").Output
$apiLevelInt = [int]$apiLevel
$mediaProjectionRequired = $apiLevelInt -lt 30
$expectedCaptureBackend = if ($mediaProjectionRequired) { "MediaProjection" } else { "AccessibilityScreenshot" }
$enabledAccessibility = (Invoke-AdbCommand -Arguments @(
    "shell", "settings", "get", "secure", "enabled_accessibility_services"
) -Label "读取无障碍状态").Output
$defaultIme = (Invoke-AdbCommand -Arguments @(
    "shell", "settings", "get", "secure", "default_input_method"
) -Label "读取默认输入法").Output

$localHash = $null
$installedHash = $null
$hashMatches = $null
if (Test-Path -LiteralPath $debugApkPath -PathType Leaf) {
    $localHash = (Get-FileHash -LiteralPath $debugApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    $packagePathOutput = (Invoke-AdbCommand -Arguments @("shell", "pm", "path", $appPackage) -Label "读取已安装 APK 路径").Output
    $installedApkPath = (($packagePathOutput -split "`r?`n" | Select-Object -First 1) -replace '^package:', '').Trim()
    $remoteHashResult = Invoke-AdbCommand -Arguments @(
        "shell", "sha256sum", $installedApkPath
    ) -Label "计算已安装 APK 哈希" -AllowFailure
    if ($remoteHashResult.ExitCode -eq 0 -and $remoteHashResult.Output -match '^([0-9a-fA-F]{64})\s') {
        $installedHash = $Matches[1].ToUpperInvariant()
        $hashMatches = $installedHash -eq $localHash
        if ($Install -and -not $hashMatches) {
            throw "安装后的 APK 哈希与本地产物不一致。"
        }
    }
}

$projection = Get-MediaProjectionState
if ($RequestProjection -and -not $projection.Active) {
    Write-Host "正在打开 MediaProjection 系统确认页；如设备显示确认框，请由用户确认。"
    Invoke-AdbCommand -Arguments @(
        "shell", "am", "start", "-n", $mainActivity,
        "--ez", "debug_request_screen_capture", "true"
    ) -Label "打开截图授权页" | Out-Null
    $deadline = [DateTimeOffset]::Now.AddSeconds($ProjectionWaitSeconds)
    do {
        Start-Sleep -Seconds 1
        $projection = Get-MediaProjectionState
    } while (-not $projection.Active -and [DateTimeOffset]::Now -lt $deadline)
}

$templateCancellationPassed = $null
if ($RunTemplateCancellation) {
    if (-not $testHostInstalled) {
        throw "模板取消回归需要 $testHostPackage。可使用 -Install。"
    }
    if ($mediaProjectionRequired -and -not $projection.Active) {
        throw "模板取消回归需要活动的 MediaProjection 会话。可使用 -RequestProjection。"
    }

    $templatesBefore = Get-TemplateListing
    Invoke-AdbCommand -Arguments @("shell", "am", "start", "-n", $testHostActivity) -Label "打开测试宿主" | Out-Null
    Start-Sleep -Milliseconds 700
    Invoke-AdbCommand -Arguments @(
        "shell", "am", "broadcast", "-n", $debugReceiver,
        "-a", "com.danmukey.debug.action.START_TEMPLATE_CAPTURE"
    ) -Label "启动模板采样" | Out-Null
    Start-Sleep -Seconds 2
    Invoke-AdbCommand -Arguments @("shell", "am", "start", "-n", $templateActivity) -Label "打开模板选区" | Out-Null
    Start-Sleep -Seconds 1
    $openedActivity = Get-ResumedActivity
    if ($openedActivity -notmatch [regex]::Escape("$appPackage/com.danmukey.app.TemplateCaptureActivity")) {
        throw "模板选区 Activity 未打开。前台状态：$openedActivity"
    }

    Invoke-AdbCommand -Arguments @(
        "shell", "am", "broadcast", "-n", $debugReceiver,
        "-a", "com.danmukey.debug.action.STOP_TASK"
    ) -Label "停止模板流程" | Out-Null
    Start-Sleep -Seconds 1
    $afterStopActivity = Get-ResumedActivity
    if ($afterStopActivity -notmatch [regex]::Escape("$testHostPackage/.MainActivity")) {
        throw "停止后没有返回测试宿主。前台状态：$afterStopActivity"
    }

    Invoke-AdbCommand -Arguments @("shell", "am", "start", "-n", $templateActivity) -Label "验证草稿已失效" | Out-Null
    Start-Sleep -Seconds 1
    $afterReopenActivity = Get-ResumedActivity
    if ($afterReopenActivity -notmatch [regex]::Escape("$testHostPackage/.MainActivity")) {
        throw "停止后仍能重新打开模板草稿。前台状态：$afterReopenActivity"
    }

    $templatesAfter = Get-TemplateListing
    if ($templatesAfter -ne $templatesBefore) {
        throw "模板取消回归修改了私有模板目录。"
    }
    $templateCancellationPassed = $true
}

$readinessFailures = [System.Collections.Generic.List[string]]::new()
if ($hashMatches -ne $true) {
    $readinessFailures.Add("已安装 Debug APK 与本地产物不一致，或设备无法计算哈希。")
}
if ($enabledAccessibility -notlike "*$appPackage/com.danmukey.runtime.DanmuAccessibilityService*") {
    $readinessFailures.Add("怪团建无障碍服务未启用。")
}
if ($defaultIme -ne "$appPackage/com.danmukey.runtime.DanmuKeyboardService") {
    $readinessFailures.Add("怪团建不是默认输入法。")
}
if ($mediaProjectionRequired -and -not $projection.Active) {
    $readinessFailures.Add("MediaProjection 会话未激活。")
}
if ($RunTemplateCancellation -and $templateCancellationPassed -ne $true) {
    $readinessFailures.Add("模板选区安全中止回归未通过。")
}

$summary = [ordered]@{
    verifiedAt = (Get-Date).ToString("o")
    serial = $script:SelectedSerial
    model = $model
    androidVersion = $androidVersion
    apiLevel = $apiLevelInt
    appInstalled = $appInstalled
    testHostInstalled = $testHostInstalled
    localDebugApkSha256 = $localHash
    installedDebugApkSha256 = $installedHash
    installedApkMatchesLocal = $hashMatches
    accessibilityServiceEnabled = $enabledAccessibility -like "*$appPackage/com.danmukey.runtime.DanmuAccessibilityService*"
    defaultIme = $defaultIme
    danmuKeyboardIsDefault = $defaultIme -eq "$appPackage/com.danmukey.runtime.DanmuKeyboardService"
    expectedCaptureBackend = $expectedCaptureBackend
    mediaProjectionRequired = $mediaProjectionRequired
    mediaProjectionActive = $projection.Active
    accessibilityScreenshotPassed = (
        $apiLevelInt -ge 30 -and
        -not $projection.Active -and
        $templateCancellationPassed -eq $true
    )
    templateCancellationPassed = $templateCancellationPassed
    readyChecksRequired = [bool]$RequireReady
    readyChecksPassed = $readinessFailures.Count -eq 0
    failures = @($readinessFailures)
}

$summaryJson = $summary | ConvertTo-Json -Depth 4
$summaryJson

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
    $summaryJson | Set-Content -LiteralPath $outputFullPath -Encoding UTF8
}

if ($RequireReady -and $readinessFailures.Count -gt 0) {
    throw ($readinessFailures -join "`n")
}
