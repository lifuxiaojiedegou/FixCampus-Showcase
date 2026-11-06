param(
    [string]$ConfigPath,
    [string]$TestConfigPath,
    [ValidateRange(1024, 65535)][int]$Port = 8080,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot 'verify-local.ps1') -ConfigPath $TestConfigPath
    }
    Set-FixCampusEnvironment -ConfigPath $ConfigPath
    $env:FIXCAMPUS_PORT = [string]$Port
    $applicationJar = Join-Path $projectRoot 'target\fixcampus-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $applicationJar)) {
        throw '未找到可执行 JAR，请先运行 scripts/verify-local.ps1。'
    }
    & java -jar $applicationJar
    if ($LASTEXITCODE -ne 0) { throw '应用启动或运行失败，请检查输出。' }
} finally {
    Pop-Location
}
