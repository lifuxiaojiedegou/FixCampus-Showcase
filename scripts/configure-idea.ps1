param([string]$ConfigPath)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-FixCampusEnvironment -ConfigPath $ConfigPath

$projectRoot = Split-Path -Parent $PSScriptRoot
$relativeConfigPath = '.idea/runConfigurations/FixCampus_Local.xml'
Push-Location -LiteralPath $projectRoot
try {
    Assert-FixCampusPrivatePathIgnored -RelativePath $relativeConfigPath

    $document = [System.Xml.XmlDocument]::new()
    $component = $document.CreateElement('component')
    $component.SetAttribute('name', 'ProjectRunConfigurationManager')
    [void]$document.AppendChild($component)
    $configuration = $document.CreateElement('configuration')
    $configuration.SetAttribute('default', 'false')
    $configuration.SetAttribute('name', 'FixCampus Local')
    $configuration.SetAttribute('type', 'Application')
    $configuration.SetAttribute('factoryName', 'Application')
    [void]$component.AppendChild($configuration)

    foreach ($entry in ([ordered]@{
        MAIN_CLASS_NAME = 'com.fixcampus.FixCampusApplication'
        WORKING_DIRECTORY = '$PROJECT_DIR$'
    }).GetEnumerator()) {
        $option = $document.CreateElement('option')
        $option.SetAttribute('name', $entry.Key)
        $option.SetAttribute('value', $entry.Value)
        [void]$configuration.AppendChild($option)
    }
    $module = $document.CreateElement('module')
    $module.SetAttribute('name', 'FixCampus')
    [void]$configuration.AppendChild($module)

    $environment = $document.CreateElement('envs')
    [void]$configuration.AppendChild($environment)
    foreach ($name in @('FIXCAMPUS_DB_HOST', 'FIXCAMPUS_DB_PORT', 'FIXCAMPUS_DB_NAME',
                        'FIXCAMPUS_DB_USERNAME', 'FIXCAMPUS_DB_PASSWORD', 'FIXCAMPUS_DEMO_ENABLED',
                        'FIXCAMPUS_DEMO_ADMIN_PASSWORD', 'FIXCAMPUS_DEMO_WORKER_PASSWORD',
                        'FIXCAMPUS_DEMO_STUDENT_PASSWORD')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($null -eq $value) { continue }
        $variable = $document.CreateElement('env')
        $variable.SetAttribute('name', $name)
        $variable.SetAttribute('value', $value)
        [void]$environment.AppendChild($variable)
    }
    $method = $document.CreateElement('method')
    $method.SetAttribute('v', '2')
    $make = $document.CreateElement('option')
    $make.SetAttribute('name', 'Make')
    $make.SetAttribute('enabled', 'true')
    [void]$method.AppendChild($make)
    [void]$configuration.AppendChild($method)

    $outputPath = Join-Path $projectRoot $relativeConfigPath
    [void][System.IO.Directory]::CreateDirectory((Split-Path -Parent $outputPath))
    $settings = [System.Xml.XmlWriterSettings]::new()
    $settings.Indent = $true
    $settings.OmitXmlDeclaration = $true
    $settings.Encoding = [System.Text.UTF8Encoding]::new($false)
    $writer = [System.Xml.XmlWriter]::Create($outputPath, $settings)
    try {
        $document.Save($writer)
    } finally {
        $writer.Dispose()
    }
    Write-Output '已生成本机 IDEA 配置：FixCampus Local（继承项目 JDK，请设为 25）。'
    Write-Output '在 IDEA 顶部选择 FixCampus Local 后运行；本地凭据保留在 Git 忽略的 .idea/ 中。'
    Write-Output '修改 .local/database.json 后，请重新运行本脚本更新 IDEA 配置。'
} finally {
    Pop-Location
}
