param(
    [Parameter(Mandatory = $true)]
    [string]$Package,
    [string]$AppName = "",
    [string]$LaunchComponent = "",
    [string]$Scenario = "home",
    [int]$SettleSeconds = 8,
    [int]$UiDumpAttempts = 3,
    [switch]$NoForceStop,
    [switch]$NoScreenshot
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
$LogsDir = Join-Path $ProjectDir "logs\commerce_onboarding"
$RunStamp = Get-Date -Format "yyyyMMdd_HHmmss"
$SafeName = if ($AppName) { $AppName } else { $Package }
$SafeName = ($SafeName -replace '[^A-Za-z0-9_-]+', '_').Trim('_').ToLowerInvariant()
$SafeScenario = ($Scenario -replace '[^A-Za-z0-9_-]+', '_').Trim('_').ToLowerInvariant()
if (-not $SafeScenario) { $SafeScenario = "home" }
$RunDir = Join-Path $LogsDir "$SafeName`_$SafeScenario`_$RunStamp"

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null

function Write-Phase([string]$Message) {
    Write-Host "[$SafeName/$SafeScenario] $Message"
}

function Require-Device {
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No connected emulator/device found."
    }
}

function Require-Package([string]$Name) {
    $installed = adb shell pm list packages $Name
    if ($installed -notcontains "package:$Name") {
        throw "Required package is not installed on the device: $Name"
    }
}

function Resolve-LaunchComponent([string]$Name) {
    $resolved = (adb shell cmd package resolve-activity --brief $Name 2>&1) -join "`n"
    $resolved | Set-Content -Path (Join-Path $RunDir "resolve_activity.txt") -Encoding UTF8
    $line = ($resolved -split "`n" | Where-Object { $_ -match "/" } | Select-Object -Last 1)
    if (-not $line) {
        throw "Could not resolve launcher activity for $Name. See resolve_activity.txt."
    }
    return $line.Trim()
}

function Save-TextFile([string]$Name, [string]$Text) {
    $path = Join-Path $RunDir $Name
    $Text | Set-Content -Path $path -Encoding UTF8
    return $path
}

function Save-CommandOutput([string]$Name, [scriptblock]$Command) {
    $text = (& $Command 2>&1) -join "`n"
    return Save-TextFile $Name $text
}

function Get-UiDump {
    $attempts = [Math]::Max(1, $UiDumpAttempts)
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        adb shell "rm -f /sdcard/window.xml" | Out-Null
        $dumpResult = (adb shell timeout 8 uiautomator dump /sdcard/window.xml 2>&1) -join "`n"
        Save-TextFile "uiautomator_dump_result_attempt_$attempt.txt" $dumpResult | Out-Null
        if ($dumpResult -match "UI hierchary dumped to|UI hierarchy dumped to") {
            $xml = (adb shell cat /sdcard/window.xml 2>&1) -join "`n"
            if ($xml -match "<hierarchy") {
                return $xml
            }
        }
        if ($attempt -lt $attempts) {
            Start-Sleep -Milliseconds 800
        }
    }
    return ""
}

function Get-XmlAttributeValues([string]$Xml, [string]$AttributeName) {
    $pattern = [regex]::Escape($AttributeName) + '="([^"]+)"'
    $matches = [regex]::Matches($Xml, $pattern)
    $values = New-Object System.Collections.Generic.List[string]
    foreach ($match in $matches) {
        $value = [System.Net.WebUtility]::HtmlDecode($match.Groups[1].Value).Trim()
        if ($value) {
            $values.Add($value)
        }
    }
    return @($values | Select-Object -Unique)
}

function Save-NodeTable([string]$Xml) {
    try {
        $doc = [xml]$Xml
        $rows = foreach ($node in $doc.SelectNodes("//node")) {
            [pscustomobject]@{
                text = [System.Net.WebUtility]::HtmlDecode(($node.text | Out-String).Trim())
                content_desc = [System.Net.WebUtility]::HtmlDecode(($node.'content-desc' | Out-String).Trim())
                resource_id = ($node.'resource-id' | Out-String).Trim()
                class = ($node.class | Out-String).Trim()
                package = ($node.package | Out-String).Trim()
                clickable = ($node.clickable | Out-String).Trim()
                enabled = ($node.enabled | Out-String).Trim()
                focused = ($node.focused | Out-String).Trim()
                bounds = ($node.bounds | Out-String).Trim()
            }
        }
        $path = Join-Path $RunDir "ui_nodes.csv"
        $rows | Export-Csv -Path $path -NoTypeInformation -Encoding UTF8
        return $path
    } catch {
        Save-TextFile "ui_nodes_error.txt" $_.Exception.Message | Out-Null
        return ""
    }
}

function Save-Screenshot([string]$Name) {
    $path = Join-Path $RunDir $Name
    adb exec-out screencap -p > $path
    return $path
}

Require-Device
Require-Package $Package

if (-not $LaunchComponent) {
    $LaunchComponent = Resolve-LaunchComponent $Package
}

Write-Phase "package=$Package launch=$LaunchComponent"

if (-not $NoForceStop) {
    Write-Phase "force-stopping package"
    adb shell am force-stop $Package | Out-Null
    Start-Sleep -Seconds 1
}

Write-Phase "launching app"
adb shell am start -n $LaunchComponent | Out-Null
Start-Sleep -Seconds $SettleSeconds

$packagePath = Save-CommandOutput "package_dump_excerpt.txt" {
    adb shell dumpsys package $Package |
        Select-String -Pattern "versionName|versionCode|userId|MAIN|LAUNCHER|dataDir|targetSdk|minSdk" -Context 0,1
}
$windowPath = Save-CommandOutput "window_focus.txt" {
    adb shell dumpsys window |
        Select-String -Pattern "mCurrentFocus|mFocusedApp|topResumedActivity|mResumedActivity"
}

$xml = Get-UiDump
$xmlPath = ""
$textPath = ""
$resourcePath = ""
$summary = [ordered]@{
    app_name = $AppName
    package = $Package
    launch_component = $LaunchComponent
    scenario = $Scenario
    captured_at = (Get-Date).ToString("s")
    run_dir = $RunDir
    package_dump_excerpt = $packagePath
    window_focus = $windowPath
    ui_dump_attempts = [Math]::Max(1, $UiDumpAttempts)
    ui_xml = $null
    ui_text = $null
    ui_resource_ids = $null
    ui_node_table = $null
    ui_text_count = 0
    ui_resource_id_count = 0
    screenshot = $null
}

if ($xml) {
    $xmlPath = Save-TextFile "ui.xml" $xml
    $texts = @()
    $texts += Get-XmlAttributeValues $xml "text"
    $texts += Get-XmlAttributeValues $xml "content-desc"
    $resources = Get-XmlAttributeValues $xml "resource-id"
    $textPath = Save-TextFile "ui_text.txt" (($texts | Where-Object { $_ } | Select-Object -Unique) -join "`n")
    $resourcePath = Save-TextFile "ui_resource_ids.txt" (($resources | Where-Object { $_ } | Select-Object -Unique) -join "`n")
    $nodeTablePath = Save-NodeTable $xml
    $summary.ui_xml = $xmlPath
    $summary.ui_text = $textPath
    $summary.ui_resource_ids = $resourcePath
    if ($nodeTablePath) {
        $summary.ui_node_table = $nodeTablePath
    }
    $summary.ui_text_count = @($texts | Where-Object { $_ } | Select-Object -Unique).Count
    $summary.ui_resource_id_count = @($resources | Where-Object { $_ } | Select-Object -Unique).Count
}

if (-not $NoScreenshot) {
    Write-Phase "capturing screenshot"
    $summary.screenshot = Save-Screenshot "screen.png"
}

$summaryPath = Join-Path $RunDir "summary.json"
$summary | ConvertTo-Json -Depth 5 | Set-Content -Path $summaryPath -Encoding UTF8

Write-Phase "summary: $summaryPath"
if ($textPath) {
    Write-Phase "ui text: $textPath"
}
if ($resourcePath) {
    Write-Phase "resource ids: $resourcePath"
}
