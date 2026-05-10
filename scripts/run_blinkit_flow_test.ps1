$ErrorActionPreference = "Stop"

$Package = "com.example.beta"
$Service = "com.example.beta/.MyAccessibilityService"
$BlinkitPackage = "com.grofers.customerapp"
$ProjectDir = Split-Path -Parent $PSScriptRoot
$LogsDir = Join-Path $ProjectDir "logs"

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null

$env:GRADLE_USER_HOME = Join-Path $ProjectDir ".gradle-user-home"
$env:ANDROID_USER_HOME = Join-Path $ProjectDir ".android-user-home"
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue

function Require-Device {
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No connected emulator/device found. Start an emulator and rerun."
    }
}

function Require-Package([string]$Name) {
    $installed = adb shell pm list packages $Name
    if ($installed -notmatch [regex]::Escape($Name)) {
        throw "Required package is not installed on the device: $Name"
    }
}

function Get-UiDump {
    adb shell uiautomator dump /sdcard/window.xml | Out-Null
    return (adb shell cat /sdcard/window.xml) -join "`n"
}

function Get-NodeCenterByText([string]$Text, [string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    $pattern = 'text="[^"]*' + [regex]::Escape($Text) + '[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $match = [regex]::Match($Xml, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) {
        return $null
    }
    return @{
        X = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
        Y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
    }
}

function Tap-Text([string]$Text, [int]$TimeoutSeconds = 10) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $point = Get-NodeCenterByText $Text
        if ($point) {
            adb shell input tap $point.X $point.Y
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Tap-BetaOverlay {
    adb shell input tap 731 206
}

function Select-BlinkitHomeIfNeeded {
    $xml = Get-UiDump
    if ($xml -notmatch "Unserviceable area") {
        return
    }

    adb shell input tap 360 220
    Start-Sleep -Seconds 2
    if (-not (Tap-Text "Home" 8)) {
        adb shell input tap 520 1945
    }
    Start-Sleep -Seconds 6
}

function Wait-ForLog([string]$Pattern, [int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $logs = adb logcat -d | Select-String $Pattern
        if ($logs) {
            return $true
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Test-BackendCartVerified {
    $backendDir = Join-Path (Split-Path -Parent $ProjectDir) "beta backend"
    $latestPath = Join-Path $backendDir "debug_runs\latest.json"
    if (-not (Test-Path $latestPath)) {
        return $false
    }
    $latest = Get-Content -Raw $latestPath | ConvertFrom-Json
    $responsePath = Join-Path (Join-Path $backendDir $latest.run_dir) "final_response.json"
    if (-not (Test-Path $responsePath)) {
        return $false
    }
    $response = Get-Content -Raw $responsePath | ConvertFrom-Json
    return ($response.task_completed -eq $true -and $response.verification_status.item_found_in_cart -eq $true)
}

function Wait-ForBackendCartVerified([int]$TimeoutSeconds = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-BackendCartVerified) {
            return $true
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $false
}

Require-Device
Require-Package $BlinkitPackage

.\gradlew.bat --no-daemon assembleDebug assembleDebugAndroidTest -x startLogcatCapture
.\gradlew.bat --no-daemon installDebug -x startLogcatCapture

adb shell appops set $Package SYSTEM_ALERT_WINDOW allow | Out-Null

adb shell settings put secure accessibility_enabled 0
adb shell settings delete secure enabled_accessibility_services | Out-Null
Start-Sleep -Seconds 2
adb shell settings put secure enabled_accessibility_services "$Service"
adb shell settings put secure accessibility_enabled 1
Start-Sleep -Seconds 3

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb logcat -c

try {
    adb shell am start -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
    if (-not (Tap-Text "START CAPTURE" 8)) {
        adb shell input tap 540 168
    }

    Start-Sleep -Seconds 2
    if (-not (Tap-Text "Start now" 8)) {
        adb shell input tap 854 1518
    }

    if (-not (Wait-ForLog "Screen capture started successfully|ScreenCaptureService instance set" 20)) {
        adb shell screencap -p /sdcard/beta_ready_failure.png
        adb pull /sdcard/beta_ready_failure.png "$LogsDir\beta_ready_failure.png" | Out-Null
        throw "Beta screen capture did not report readiness."
    }

    adb shell am force-stop $BlinkitPackage | Out-Null
    Start-Sleep -Seconds 1
    adb shell monkey -p $BlinkitPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 5
    Select-BlinkitHomeIfNeeded

    Tap-BetaOverlay
    Start-Sleep -Seconds 1

    if (-not (Wait-ForLog "Showing emulator input dialog|Emulator input overlay shown" 10)) {
        throw "Beta emulator instruction dialog did not open."
    }

    adb shell input tap 540 1100
    adb shell input text "order%sbutter"
    Start-Sleep -Seconds 1
    adb shell input tap 540 1340

    $result = 0
    if (-not (Wait-ForLog "INSTRUCTION_RECEIVED|BLINKIT_SEARCH_STARTED" 20)) {
        throw "Beta did not receive the emulator instruction."
    }

    if (-not (Wait-ForLog "BLINKIT_CART_INCREMENT_CONFIRMED|FLOW_SUCCESS|STATE: SUCCESS|cart increment confirmed|Item found in cart: true" 120) -and -not (Wait-ForBackendCartVerified 30)) {
        $result = 1
    }
} finally {
    adb logcat -d AndroidRuntime:E "*:S" > (Join-Path $LogsDir "blinkit_flow_crash_log.txt")
    adb logcat -d | Select-String "$Package|BetaAgent|AndroidRuntime|FATAL EXCEPTION|Accessibility|MediaProjection|Blinkit|grofers|Cart|butter" > (Join-Path $LogsDir "blinkit_flow_full_log.txt")
    adb exec-out screencap -p > (Join-Path $LogsDir "blinkit_flow_final_screen.png")
}

if ($result -ne 0) {
    throw "Blinkit flow test failed. See logs\blinkit_flow_full_log.txt and logs\blinkit_flow_crash_log.txt."
}

Write-Host "Blinkit flow test passed without checkout/payment."
