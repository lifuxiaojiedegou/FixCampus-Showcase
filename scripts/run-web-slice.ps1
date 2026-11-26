param(
    [Parameter(Mandatory)][string]$ConfigPath,
    [string]$DemoConfigPath
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$port = 0
if ($config.database -cnotmatch '^fixcampus_m421_[a-z0-9_]+_test$' -or
    -not [int]::TryParse([string]$config.port, [ref]$port) -or
    $port -lt 1 -or $port -gt 65535 -or $port -eq 3306) {
    throw 'This launcher only accepts the separately provisioned disposable M4.2.1 instance.'
}
foreach ($key in @('username', 'password', 'migrationUsername', 'migrationPassword')) {
    if ([string]::IsNullOrWhiteSpace([string]$config.$key)) { throw "Missing private setting: $key" }
}
$settings = @{
    FIXCAMPUS_DB_HOST = '127.0.0.1'
    FIXCAMPUS_DB_PORT = [string]$port
    FIXCAMPUS_DB_NAME = [string]$config.database
    FIXCAMPUS_DB_USERNAME = [string]$config.username
    FIXCAMPUS_MIGRATION_USERNAME = [string]$config.migrationUsername
    SPRING_PROFILES_ACTIVE = 'web-slice'
    FIXCAMPUS_PORT = '18080'
    FIXCAMPUS_WEB_ORIGIN = 'http://127.0.0.1:5173'
    # The local loopback launcher uses HTTP. Use Secure cookies with HTTPS.
    FIXCAMPUS_COOKIE_SECURE = 'false'
    FIXCAMPUS_DEMO_ENABLED = 'false'
}
$settings.Add('FIXCAMPUS_DB_PASSWORD', [string]$config.password)
$settings.Add('FIXCAMPUS_MIGRATION_PASSWORD', [string]$config.migrationPassword)
if ($DemoConfigPath) {
    $demo = Get-Content -LiteralPath $DemoConfigPath -Raw | ConvertFrom-Json
    if ($demo.enabled -isnot [bool] -or -not $demo.enabled) {
        throw 'Set enabled to true in the explicit local demo configuration.'
    }
    foreach ($role in @('admin', 'worker', 'student')) {
        $credential = $demo.($role + 'Password')
        if ($credential -isnot [string] -or [string]::IsNullOrWhiteSpace($credential) -or
            $credential.StartsWith('REPLACE_WITH_')) {
            throw "Set a private demo password for role: $role"
        }
        $settings.Add('FIXCAMPUS_DEMO_' + $role.ToUpperInvariant() + '_PASSWORD', $credential)
    }
    $settings.FIXCAMPUS_DEMO_ENABLED = 'true'
}

$previous = @{}
Push-Location -LiteralPath $projectRoot
try {
    foreach ($key in $settings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, $settings[$key])
    }
    & .\mvnw.cmd --batch-mode spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Local slice startup failed.' }
} finally {
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key])
    }
    Pop-Location
}
