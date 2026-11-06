$ErrorActionPreference = 'Stop'

function Assert-FixCampusPrivatePathIgnored {
    param([ValidateSet('.local/database-test.json', '.idea/runConfigurations/FixCampus_Local.xml')][string]$RelativePath)
    $projectRoot = Split-Path -Parent $PSScriptRoot
    if (Test-Path -LiteralPath (Join-Path $projectRoot '.git')) {
        & git -C $projectRoot check-ignore --quiet -- $RelativePath
        if ($LASTEXITCODE -ne 0) { throw '本机配置路径未被 Git 忽略，已停止写入。' }
        return
    }
    # A source ZIP has no .git directory. Preserve the same private-directory rule.
    $ignorePath = Join-Path $projectRoot '.gitignore'
    if (-not (Test-Path -LiteralPath $ignorePath)) { throw '源码包缺少 .gitignore，已停止写入本机配置。' }
    $rules = @(Get-Content -LiteralPath $ignorePath | ForEach-Object { $_.Trim() })
    $directoryRule = if ($RelativePath.StartsWith('.local/')) { '/.local/' } else { '/.idea/' }
    if ($rules -notcontains $directoryRule -or @($rules | Where-Object { $_.StartsWith('!') -and $_ -ne '!.env.example' }).Count) {
        throw '无法确认源码包中的本机配置排除规则，请保留原始 .gitignore 后重试。'
    }
}

function Set-FixCampusEnvironment {
    param([string]$ConfigPath)

    $projectRoot = Split-Path -Parent $PSScriptRoot
    if (-not $ConfigPath) {
        $ConfigPath = Join-Path $projectRoot '.local\database.json'
    }
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw '缺少本地数据库配置。请按 README 创建 .local/database.json，或直接使用环境变量运行。'
    }
    $databaseConfig = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
    foreach ($requiredKey in @('host', 'port', 'database', 'username', 'password')) {
        if ([string]::IsNullOrWhiteSpace([string]$databaseConfig.$requiredKey)) {
            throw "本地数据库配置缺少字段：$requiredKey"
        }
    }
    if ($databaseConfig.password -eq 'REPLACE_WITH_YOUR_LOCAL_PASSWORD') {
        throw '请先填写本地数据库密码；不要使用示例占位值。'
    }
    $env:FIXCAMPUS_DB_HOST = [string]$databaseConfig.host
    $env:FIXCAMPUS_DB_PORT = [string]$databaseConfig.port
    $env:FIXCAMPUS_DB_NAME = [string]$databaseConfig.database
    $env:FIXCAMPUS_DB_USERNAME = [string]$databaseConfig.username
    $env:FIXCAMPUS_DB_PASSWORD = [string]$databaseConfig.password
    $env:MAVEN_USER_HOME = Join-Path $projectRoot '.cache\maven'

    $demoConfigPath = Join-Path $projectRoot '.local\demo-accounts.json'
    if (Test-Path -LiteralPath $demoConfigPath) {
        $demoConfig = Get-Content -LiteralPath $demoConfigPath -Raw | ConvertFrom-Json
        if ($demoConfig.enabled -isnot [bool]) { throw '演示配置的 enabled 必须是 JSON 布尔值 true 或 false。' }
        $env:FIXCAMPUS_DEMO_ENABLED = [string]([bool]$demoConfig.enabled)
        foreach ($entry in @{
            FIXCAMPUS_DEMO_ADMIN_PASSWORD = 'adminPassword'
            FIXCAMPUS_DEMO_WORKER_PASSWORD = 'workerPassword'
            FIXCAMPUS_DEMO_STUDENT_PASSWORD = 'studentPassword'
        }.GetEnumerator()) {
            if ($demoConfig.enabled -and ([string]::IsNullOrWhiteSpace([string]$demoConfig.($entry.Value)) -or
                [string]$demoConfig.($entry.Value) -like 'REPLACE_WITH_*')) {
                throw '启用演示初始化前，请填写三个独立的本地演示密码，不要保留示例占位值。'
            }
            [Environment]::SetEnvironmentVariable($entry.Key, [string]$demoConfig.($entry.Value), 'Process')
        }
    }
}
