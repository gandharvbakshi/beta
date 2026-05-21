param(
    [string]$Instruction = "order apple",
    [int]$TimeoutSeconds = 300,
    [switch]$SkipBuild,
    [switch]$AllowFailedItems,
    [switch]$AllowStoreUnavailable
)

$ErrorActionPreference = "Stop"

$Package = "live.betaapp.android"
$MainActivityComponent = "$Package/com.example.beta.MainActivity"
$Service = "$Package/com.example.beta.MyAccessibilityService"
$SwiggyPackage = "in.swiggy.android.instamart"
$ProjectDir = Split-Path -Parent $PSScriptRoot
$LogsDir = Join-Path $ProjectDir "logs"
$ScenarioName = ($Instruction -replace '[^A-Za-z0-9_-]+', '_').Trim('_').ToLowerInvariant()
if (-not $ScenarioName) {
    $ScenarioName = "swiggy_flow"
}

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null

$env:GRADLE_USER_HOME = Join-Path $ProjectDir ".gradle-user-home"
$env:ANDROID_USER_HOME = Join-Path $ProjectDir ".android-user-home"
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue

function Write-Phase([string]$Message) {
    Write-Host "[$ScenarioName] $Message"
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

function Wait-BetaAccessibilityConnected([int]$TimeoutSeconds = 15) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $enabled = (adb shell settings get secure enabled_accessibility_services) -join "`n"
        $accessibility = (adb shell settings get secure accessibility_enabled) -join "`n"
        $runtime = (adb shell dumpsys accessibility) -join "`n"
        if ($enabled -match [regex]::Escape($Service) `
                -and $accessibility.Trim() -eq "1" `
                -and $runtime -match "Bound services:\{[^}]*My Accessibility Service" `
                -and $runtime -match [regex]::Escape("Enabled services:{{$Service}}")) {
            return $true
        }
        Enable-BetaAccessibility
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Dismiss-AnrFromWindowState {
    $focusedAnr = adb shell dumpsys window |
        Select-String "mCurrentFocus=.*Application Not Responding|mFocusedApp=.*Application Not Responding"
    if (-not $focusedAnr) {
        return $false
    }

    adb shell input tap 203 1518 | Out-Null
    Start-Sleep -Milliseconds 500
    adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 500
    return $true
}

function Get-UiDump {
    for ($i = 0; $i -lt 3; $i++) {
        if (Dismiss-AnrFromWindowState) {
            continue
        }

        adb shell "rm -f /sdcard/window.xml" | Out-Null
        $dumpResult = (adb shell timeout 8 uiautomator dump /sdcard/window.xml 2>&1) -join "`n"
        if ($dumpResult -match "UI hierchary dumped to|UI hierarchy dumped to") {
            $hasDump = (adb shell "if [ -s /sdcard/window.xml ]; then echo WINDOW_XML_PRESENT; fi" 2>&1) -join "`n"
            if ($hasDump -notmatch "WINDOW_XML_PRESENT") {
                Start-Sleep -Seconds 1
                continue
            }
            $xml = (adb shell cat /sdcard/window.xml) -join "`n"
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

function Wait-BetaMediaProjectionPermissionDialog([int]$TimeoutSeconds = 12) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $windowState = (adb shell dumpsys window) -join "`n"
        if ($windowState -match "MediaProjectionPermission|Media projection permission|MediaProjectionPermissionActivity") {
            return $true
        }
        Start-Sleep -Milliseconds 400
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Tap-MediaProjectionPermissionDialog([int]$TimeoutSeconds = 12) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (-not (Wait-BetaMediaProjectionPermissionDialog 1)) {
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
        if (-not (Wait-BetaMediaProjectionPermissionDialog 1)) {
            return $true
        }
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Tap-BetaStartupOnboardingStep {
    $xml = Get-UiDump
    $stepPoint = Get-NodeCenterByTextOrDesc @(
        "Get started",
        "Continue",
        "Next",
        "Allow",
        "While using the app",
        "Only this time",
        "Allow this time",
        "Allow while using the app",
        "Allow only while using the app",
        "Done"
    ) $xml
    if ($stepPoint) {
        adb shell input tap $stepPoint.X $stepPoint.Y | Out-Null
        return $true
    }

    return $false
}

function Wait-BetaScreenCaptureReady([int]$TimeoutSeconds = 75) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $logs = adb logcat -d | Select-String "Screen capture started successfully|Screen capture started successfully after retry|VirtualDisplay created successfully|VirtualDisplay created with minimal flags"
        if ($logs -and (Test-BetaScreenCaptureServiceRunning)) {
            return $true
        }
        Start-Sleep -Seconds 2
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
    Write-Phase "launching beta app and starting screen capture"
    adb shell am force-stop $Package | Out-Null
    Start-Sleep -Seconds 1
    adb logcat -c
    adb shell settings put secure accessibility_enabled 0 | Out-Null
    adb shell settings delete secure enabled_accessibility_services | Out-Null
    Start-Sleep -Seconds 1
    Enable-BetaAccessibility
    Wait-BetaAccessibilityConnected 15 | Out-Null
    adb shell am start -n $MainActivityComponent | Out-Null
    Start-Sleep -Seconds 9

    for ($i = 0; $i -lt 3; $i++) {
        if (Dismiss-AnrFromWindowState) {
            adb shell am start -n $MainActivityComponent | Out-Null
            Start-Sleep -Milliseconds 900
            continue
        }

        Tap-BetaStartCaptureButton
        Start-Sleep -Seconds 3
        if (-not (Tap-MediaProjectionPermissionDialog 20)) {
            Tap-BetaStartupOnboardingStep | Out-Null
            Start-Sleep -Seconds 1
            Tap-MediaProjectionPermissionDialog 8 | Out-Null
        }
        Start-Sleep -Seconds 2

        if (Wait-BetaScreenCaptureReady 36) {
            Enable-BetaAccessibility
            adb shell am broadcast -a "$Package.HIDE_AUTOMATION_OVERLAY" -p $Package | Out-Null
            adb shell input keyevent 3 | Out-Null
            Start-Sleep -Seconds 1
            return
        }

        if ((adb logcat -d | Select-String "Beta isn.t responding|Beta isn't responding|Application Not Responding: live.betaapp.android")) {
            adb shell am force-stop $Package | Out-Null
            Start-Sleep -Seconds 2
            adb shell am start -n $MainActivityComponent | Out-Null
            Start-Sleep -Seconds 9
            continue
        }
    }

    adb shell screencap -p /sdcard/swiggy_beta_ready_failure.png | Out-Null
    adb pull /sdcard/swiggy_beta_ready_failure.png "$LogsDir\swiggy_beta_ready_failure.png" | Out-Null
    throw "Beta screen capture did not report readiness."
}

function Ensure-SwiggyHomeReady([switch]$NoForceStop) {
    Write-Phase "ensuring Swiggy Instamart is on saved Home"
    $preflightArgs = @{
        HomeReadyTimeoutSeconds = 60
    }
    if ($NoForceStop) {
        $preflightArgs.NoForceStop = $true
    }
    & "$PSScriptRoot\swiggy_address_preflight.ps1" @preflightArgs
}

Require-Device
Require-Package $SwiggyPackage

if (-not $SkipBuild) {
    .\gradlew.bat --no-daemon assembleDebug -x startLogcatCapture
    .\gradlew.bat --no-daemon installDebug -x startLogcatCapture
}

adb shell appops set $Package SYSTEM_ALERT_WINDOW allow | Out-Null
adb shell settings put global window_animation_scale 0 | Out-Null
adb shell settings put global transition_animation_scale 0 | Out-Null
adb shell settings put global animator_duration_scale 0 | Out-Null

$result = 1

try {
    Ensure-SwiggyHomeReady -NoForceStop
    Start-BetaScreenCapture
    Ensure-SwiggyHomeReady -NoForceStop

    if (-not (Test-BetaScreenCaptureServiceRunning)) {
        Write-Phase "screen capture service was not retained after Swiggy preflight; retrying capture"
        Start-BetaScreenCapture
        Ensure-SwiggyHomeReady -NoForceStop
    }

    if (-not (Test-BetaScreenCaptureServiceRunning)) {
        throw "Beta screen capture service is not running before instruction submission."
    }

    Write-Phase "submitting Swiggy instruction through manual-ready runner"
    $manualArgs = @{
        Instruction = $Instruction
        TimeoutSeconds = $TimeoutSeconds
    }
    if ($AllowFailedItems) {
        $manualArgs.AllowFailedItems = $true
    }
    if ($AllowStoreUnavailable) {
        $manualArgs.AllowStoreUnavailable = $true
    }
    if ($AllowExternalAppUnresponsive) {
        $manualArgs.AllowExternalAppUnresponsive = $true
    }

    & "$PSScriptRoot\run_manual_ready_flow.ps1" @manualArgs
    $result = 0
} finally {
    adb exec-out screencap -p > (Join-Path $LogsDir "$ScenarioName`_swiggy_final_screen.png")
}

if ($result -ne 0) {
    throw "Swiggy flow test failed for '$Instruction'."
}

Write-Host "Swiggy Instamart flow test completed for '$Instruction'."
