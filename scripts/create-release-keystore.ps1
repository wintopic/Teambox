[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$StorePath,

    [string]$Alias = 'teambox-release',

    [string]$DistinguishedName = 'CN=Teambox, OU=Mobile, O=Teambox, L=Unknown, ST=Unknown, C=CN'
)

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$absoluteStorePath = [System.IO.Path]::GetFullPath($StorePath)

if ($absoluteStorePath.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw '正式签名密钥不得保存在项目工作区内。请选择独立且会备份的安全目录。'
}

if (Test-Path -LiteralPath $absoluteStorePath) {
    throw "目标文件已存在，已停止以避免覆盖签名密钥。路径为 $absoluteStorePath"
}

$storeDirectory = Split-Path -Parent $absoluteStorePath
if (-not (Test-Path -LiteralPath $storeDirectory)) {
    New-Item -ItemType Directory -Path $storeDirectory -Force | Out-Null
}

$keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
$keytoolPath = if ($keytoolCommand) {
    $keytoolCommand.Source
} elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} else {
    $bundledKeytool = Join-Path $env:LOCALAPPDATA 'Teambox\tools\jdk17\bin\keytool.exe'
    if (-not (Test-Path -LiteralPath $bundledKeytool)) {
        $bundledKeytool = Join-Path $env:LOCALAPPDATA 'DanmuKey\tools\jdk17\bin\keytool.exe'
    }
    if (Test-Path -LiteralPath $bundledKeytool) {
        $bundledKeytool
    } else {
        $null
    }
}

if (-not $keytoolPath) {
    throw '未找到 keytool.exe。请先设置 JAVA_HOME 或运行 scripts/dev-env.ps1。'
}

Write-Host '即将创建 Android 正式签名密钥。密码会由 keytool 在终端中交互询问，不会写入脚本。'
Write-Host "密钥路径 $absoluteStorePath"
Write-Host "密钥别名 $Alias"

& $keytoolPath `
    -genkeypair `
    -v `
    -keystore $absoluteStorePath `
    -storetype PKCS12 `
    -alias $Alias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -dname $DistinguishedName

if ($LASTEXITCODE -ne 0) {
    throw "keytool 创建失败，退出码为 $LASTEXITCODE"
}

Write-Host '签名密钥已创建。请立即离线备份密钥文件、别名和密码。PKCS12 通常使用相同的存储密码与密钥密码。'
