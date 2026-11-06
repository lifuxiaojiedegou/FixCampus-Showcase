param(
    [Parameter(Mandatory = $true)][string]$AdminConfigPath,
    [string]$ConfigPath,
    [string]$MySqlExecutable = 'mysql'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-FixCampusEnvironment -ConfigPath $ConfigPath
if ($env:FIXCAMPUS_DB_HOST -notin @('localhost', '127.0.0.1') -or $env:FIXCAMPUS_DB_NAME -notmatch '^fixcampus_[a-z0-9_]{1,40}$') {
    throw '历史记录升级只接受本机 fixcampus_ 前缀的已有数据库。'
}
$adminPath = (Resolve-Path -LiteralPath $AdminConfigPath).Path
$options = @("--defaults-extra-file=$adminPath", "--host=$env:FIXCAMPUS_DB_HOST", "--port=$env:FIXCAMPUS_DB_PORT",
    "--database=$env:FIXCAMPUS_DB_NAME", '--default-character-set=utf8mb4', '--batch')
$ddl = Join-Path (Split-Path -Parent $PSScriptRoot) 'database/004-order-events.sql'
Get-Content -LiteralPath $ddl -Raw | & $MySqlExecutable @options
if ($LASTEXITCODE -ne 0) { throw '处理记录表升级失败，请检查 MySQL 输出；原工单不做回填。' }
Write-Output '处理记录表已准备，原账号、工单和缺失的历史时间保持原样。'
