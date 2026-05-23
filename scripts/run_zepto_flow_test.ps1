param(
    [string]$Instruction = "order banana",
    [int]$TimeoutSeconds = 300,
    [switch]$SkipBuild,
    [switch]$NoForceStopZepto,
    [switch]$AllowFailedItems,
    [switch]$AllowStoreUnavailable,
    [switch]$AllowExternalAppUnresponsive
)

$ErrorActionPreference = "Stop"

$Package = "live.betaapp.android"
$MainActivityComponent = "$Package/com.example.beta.MainActivity"
$Service = "$Package/com.example.beta.MyAccessibilityService"
$ZeptoPackage = "com.zeptoconsumerapp"
$ProjectDir = Split-Path -Parent $PSScriptRoot

Set-Location $ProjectDir

function Write-Phase([string]$Message) {
    Write-Host "[zepto] $Message"
}

function Require-Device {
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No connected emulator/device found. Start an emulator and rerun."
    }
}

function Require-Package([string]$Name) {
    $installed = adb shell pm list packages $Name
    if ($installed -notcontains "package:$Name") {
        throw "Required package is not installed on the device: $Name"
    }
}

function Enable-BetaAccessibility {
    adb shell settings put secure enabled_accessibility_services "$Service" | Out-Null
    adb shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 2
}

function Wait-BetaAccessibilityConnected([int]$TimeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $enabled = (adb shell settings get secure enabled_accessibility_services) -join "`n"
        $accessibility = (adb shell settings get secure accessibility_enabled) -join "`n"
        $runtime = (adb shell dumpsys accessibility) -join "`n"
        if ($enabled -match [regex]::Escape($Service) -and
                $accessibility.Trim() -eq "1" -and
                $runtime -match [regex]::Escape($Service)) {
            return $true
        }
        Enable-BetaAccessibility
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Get-UiDump {
    for ($i = 0; $i -lt 4; $i++) {
        adb shell "rm -f /sdcard/window.xml" | Out-Null
        $dumpResult = (adb shell timeout 8 uiautomator dump /sdcard/window.xml 2>&1) -join "`n"
        if ($dumpResult -match "UI hierchary dumped to|UI hierarchy dumped to") {
            $xml = (adb shell cat /sdcard/window.xml 2>&1) -join "`n"
            if ($xml -match "<hierarchy") {
                return $xml
            }
        }
        Start-Sleep -Seconds 1
    }
    return ""
}

function Get-NodeCenterByTextOrDesc([string[]]$Patterns, [string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    foreach ($patternText in $Patterns) {
        $escaped = [regex]::Escape($patternText)
        $pattern = '(text|content-desc)="[^"]*' + $escaped + '[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        $match = [regex]::Match($Xml, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success) {
            return @{
                X = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
                Y = [int](([int]$match.Groups[3].Value + [int]$match.Groups[5].Value) / 2)
            }
        }
    }
    return $null
}

function Test-BetaScreenCaptureServiceRunning {
    $serviceState = (adb shell dumpsys activity services $Package) -join "`n"
    return ($serviceState -match "$([regex]::Escape($Package))/com\.example\.beta\.ScreenCaptureService|ScreenCaptureService")
}

function Test-BetaMediaProjectionActive {
    $projectionState = (adb shell dumpsys media_projection) -join "`n"
    $projectionBody = ($projectionState -split "Media Projection:", 2)[1]
    if (-not $projectionBody) {
        return $false
    }
    return ($projectionBody.Trim() -ne "null")
}

function Test-BetaScreenCaptureActive {
    return (Test-BetaScreenCaptureServiceRunning) -and (Test-BetaMediaProjectionActive)
}

function Tap-MediaProjectionPermissionDialog([int]$TimeoutSeconds = 15) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $windowState = (adb shell dumpsys window) -join "`n"
        if ($windowState -notmatch "MediaProjectionPermission|Media projection permission|MediaProjectionPermissionActivity") {
            Start-Sleep -Milliseconds 300
            continue
        }
        $xml = Get-UiDump
        $startNowPoint = Get-NodeCenterByTextOrDesc @("Start now", "Start Now") $xml
        if ($startNowPoint) {
            adb shell input tap $startNowPoint.X $startNowPoint.Y | Out-Null
        } else {
            adb shell input tap 854 1530 | Out-Null
        }
        Start-Sleep -Milliseconds 700
        $windowState = (adb shell dumpsys window) -join "`n"
        if ($windowState -notmatch "MediaProjectionPermission|Media projection permission|MediaProjectionPermissionActivity") {
            return $true
        }
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Tap-BetaStartCaptureButton {
    $xml = Get-UiDump
    $idMatch = [regex]::Match($xml, 'resource-id="live\.betaapp\.android:id/captureScreenButton"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($idMatch.Success) {
        $x = [int](([int]$idMatch.Groups[1].Value + [int]$idMatch.Groups[3].Value) / 2)
        $y = [int](([int]$idMatch.Groups[2].Value + [int]$idMatch.Groups[4].Value) / 2)
        adb shell input tap $x $y | Out-Null
        return
    }
    $startPoint = Get-NodeCenterByTextOrDesc @("Get started", "Start Screen Capture", "Start screen capture", "Start capture", "Capture screen") $xml
    if ($startPoint) {
        adb shell input tap $startPoint.X $startPoint.Y | Out-Null
        return
    }
    adb shell input tap 540 900 | Out-Null
}

function Start-BetaScreenCapture {
    if (Test-BetaScreenCaptureActive) {
        Write-Phase "Beta screen capture already active"
        return
    }

    Write-Phase "starting Beta screen capture"
    adb shell am start -n $MainActivityComponent | Out-Null
    Start-Sleep -Seconds 4
    Enable-BetaAccessibility
    if (-not (Wait-BetaAccessibilityConnected 25)) {
        throw "Beta accessibility service did not connect before capture startup."
    }
    for ($i = 0; $i -lt 3; $i++) {
        Tap-BetaStartCaptureButton
        Start-Sleep -Seconds 2
        Tap-MediaProjectionPermissionDialog 15 | Out-Null
        Start-Sleep -Seconds 5
        if (Test-BetaScreenCaptureActive) {
            Write-Phase "Beta screen capture is active"
            return
        }
    }
    throw "Beta screen capture did not become active."
}

Require-Device
Require-Package $Package
Require-Package $ZeptoPackage

if (-not $SkipBuild) {
    Write-Phase "building debug apk"
    $env:GRADLE_USER_HOME = "C:\Users\gandh\.gradle"
    .\gradlew.bat assembleDebug
    adb install -r app\build\outputs\apk\debug\app-debug.apk
}

Enable-BetaAccessibility
Start-BetaScreenCapture

Write-Phase "running Zepto address preflight"
$preflightArgs = @{}
if ($NoForceStopZepto) {
    $preflightArgs.NoForceStop = $true
}
& (Join-Path $PSScriptRoot "zepto_address_preflight.ps1") @preflightArgs

Write-Phase "submitting instruction through manual-ready runner"
$manualArgs = @{
    Instruction = $Instruction
    TimeoutSeconds = $TimeoutSeconds
    Package = $Package
}
if ($AllowFailedItems) { $manualArgs.AllowFailedItems = $true }
if ($AllowStoreUnavailable) { $manualArgs.AllowStoreUnavailable = $true }
if ($AllowExternalAppUnresponsive) { $manualArgs.AllowExternalAppUnresponsive = $true }

& (Join-Path $PSScriptRoot "run_manual_ready_flow.ps1") @manualArgs
