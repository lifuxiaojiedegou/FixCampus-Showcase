param([string]$ConfigPath)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ConfigPath) { $ConfigPath = Join-Path $projectRoot '.local\database-test.json' }
Set-FixCampusEnvironment -ConfigPath $ConfigPath
if ($env:FIXCAMPUS_DB_NAME -notmatch '^fixcampus_[a-z0-9_]+$' -or -not $env:FIXCAMPUS_DB_NAME.EndsWith('_test')) {
    throw '测试必须使用 fixcampus_ 前缀且以 _test 结尾的独立测试库，不能指向开发库。'
}
$env:FIXCAMPUS_DEMO_ENABLED = 'false'
Push-Location -LiteralPath $projectRoot
try {
    & .\mvnw.cmd --batch-mode clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven 验证失败，请查看构建输出和 target/surefire-reports。' }
} finally {
    Pop-Location
}
