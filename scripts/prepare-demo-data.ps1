param(
    [Parameter(Mandatory = $true)][string]$ConfigPath,
    [string]$MySqlExecutable = 'mysql'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-FixCampusEnvironment -ConfigPath $ConfigPath
if ($env:FIXCAMPUS_DB_HOST -notin @('localhost', '127.0.0.1') -or
    $env:FIXCAMPUS_DB_NAME -notmatch '^fixcampus_demo[a-z0-9_]*$' -or $env:FIXCAMPUS_DB_NAME.EndsWith('_test')) {
    throw '演示数据只写入本机 fixcampus_demo 前缀的独立演示库，不能指向开发库或测试库。'
}
$mysqlOptions = @("--host=$env:FIXCAMPUS_DB_HOST", "--port=$env:FIXCAMPUS_DB_PORT",
    "--user=$env:FIXCAMPUS_DB_USERNAME", "--database=$env:FIXCAMPUS_DB_NAME",
    '--default-character-set=utf8mb4', '--batch', '--skip-column-names')
$previousMySqlPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:FIXCAMPUS_DB_PASSWORD
    $accounts = & $MySqlExecutable @mysqlOptions '--execute=SELECT COUNT(*) FROM users WHERE (username=''student01'' AND role=''STUDENT'') OR (username=''worker01'' AND role=''WORKER'') OR (username=''admin01'' AND role=''ADMIN'');'
    if ($LASTEXITCODE -ne 0 -or [string]$accounts -ne '3') { throw '请先显式启用演示初始化并启动应用，准备三种演示账号。' }
    $sqlPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'database/003-demo-orders.sql'
    Get-Content -LiteralPath $sqlPath -Raw | & $MySqlExecutable @mysqlOptions
    if ($LASTEXITCODE -ne 0) { throw '演示数据未完整写入，请查看 MySQL 输出。' }
    Write-Output '六种状态的虚构演示工单已准备；重复执行不重置已有工单或账号。'
} finally {
    $env:MYSQL_PWD = $previousMySqlPassword
}
