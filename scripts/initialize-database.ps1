param(
    [Parameter(Mandatory = $true)][string]$AdminConfigPath,
    [string]$ConfigPath,
    [string]$MySqlExecutable = 'mysql',
    [string]$TestDatabase = 'fixcampus_test'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-FixCampusEnvironment -ConfigPath $ConfigPath
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath $AdminConfigPath)) { throw '未找到 MySQL 管理员客户端配置。' }
$AdminConfigPath = (Resolve-Path -LiteralPath $AdminConfigPath).Path
if ($env:FIXCAMPUS_DB_HOST -notin @('localhost', '127.0.0.1')) { throw '初始化脚本只接受本机数据库。' }
foreach ($databaseName in @($env:FIXCAMPUS_DB_NAME, $TestDatabase)) {
    if ($databaseName -notmatch '^fixcampus_[a-z0-9_]{1,40}$') { throw '库名必须为 fixcampus_ 前缀的演示或测试库。' }
}
if ($TestDatabase -eq $env:FIXCAMPUS_DB_NAME -or $TestDatabase -notmatch '_test$') {
    throw '测试库须独立于开发库，且以 _test 结尾。'
}
if ($env:FIXCAMPUS_DB_USERNAME -notmatch '^[a-zA-Z0-9_]{1,32}$') { throw '本地应用账号名称格式不支持。' }

Push-Location -LiteralPath $projectRoot
try {
    Assert-FixCampusPrivatePathIgnored -RelativePath '.local/database-test.json'
    $mysqlOptions = @("--defaults-extra-file=$AdminConfigPath", "--host=$env:FIXCAMPUS_DB_HOST",
        "--port=$env:FIXCAMPUS_DB_PORT", '--default-character-set=utf8mb4', '--batch')
    $partialRevokes = & $MySqlExecutable @mysqlOptions --skip-column-names '--execute=SELECT @@GLOBAL.partial_revokes'
    if ($LASTEXITCODE -ne 0 -or "$partialRevokes".Trim() -notin @('0', '1')) {
        throw '无法确认 MySQL 数据库授权模式，已停止初始化。'
    }
    $literalGrantNames = "$partialRevokes".Trim() -eq '1'
    $schemaSql = (@('001-users.sql', '002-repair-orders.sql', '004-order-events.sql') | ForEach-Object {
        Get-Content -LiteralPath (Join-Path $projectRoot "database\$_") -Raw
    }) -join "`n"
    $sql = ''
    foreach ($databaseName in @($env:FIXCAMPUS_DB_NAME, $TestDatabase)) {
        $sql += "CREATE DATABASE IF NOT EXISTS ``$databaseName`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`n"
        $sql += "USE ``$databaseName``;`n$schemaSql`n"
        # With partial_revokes=OFF, underscores remain wildcards even inside backticks.
        $grantName = if ($literalGrantNames) { $databaseName } else { $databaseName.Replace('_', '\_') }
        $sql += "GRANT SELECT, INSERT, UPDATE, DELETE ON ``$grantName``.* TO '$env:FIXCAMPUS_DB_USERNAME'@'localhost';`n"
        if (-not $literalGrantNames) {
            # Grant the exact schema first, then remove only this script's former broad DML grant.
            $sql += "REVOKE IF EXISTS SELECT, INSERT, UPDATE, DELETE ON ``$databaseName``.* FROM '$env:FIXCAMPUS_DB_USERNAME'@'localhost';`n"
        }
    }
    $sql | & $MySqlExecutable @mysqlOptions
    if ($LASTEXITCODE -ne 0) { throw '数据库初始化未完成，请检查 MySQL 输出；不会重置已有账号密码。' }

    $testConfig = [ordered]@{
        host = $env:FIXCAMPUS_DB_HOST
        port = [int]$env:FIXCAMPUS_DB_PORT
        database = $TestDatabase
        username = $env:FIXCAMPUS_DB_USERNAME
        password = $env:FIXCAMPUS_DB_PASSWORD
    }
    $testConfigPath = Join-Path $projectRoot '.local\database-test.json'
    if (Test-Path -LiteralPath $testConfigPath) {
        Write-Output '已有 database-test.json，保留其内容；请确认它指向本次准备的测试库。'
    } else {
        $testConfig | ConvertTo-Json | Set-Content -LiteralPath $testConfigPath -Encoding UTF8
    }
    Write-Output '开发库和独立测试库的 users、repair_orders、order_events 表已准备；应用账号仍只有数据读写权限。'
} finally {
    Pop-Location
}
