param(
    [string]$Instruction = "order butter",
    [switch]$SkipBuild,
    [switch]$SkipCartReset
)

$ErrorActionPreference = "Stop"

$Package = "com.example.beta"
$Service = "com.example.beta/.MyAccessibilityService"
$BlinkitPackage = "com.grofers.customerapp"
$ProjectDir = Split-Path -Parent $PSScriptRoot
$LogsDir = Join-Path $ProjectDir "logs"
$ScenarioName = ($Instruction -replace '[^A-Za-z0-9_-]+', '_').Trim('_').ToLowerInvariant()
if (-not $ScenarioName) {
    $ScenarioName = "blinkit_flow"
}

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null
$script:BlinkitHomeSelectionAttempted = $false

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
                -and $runtime -match [regex]::Escape("Enabled services:{{com.example.beta/com.example.beta.MyAccessibilityService}}")) {
            return $true
        }
        Enable-BetaAccessibility
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $false
}

function ConvertTo-AdbShellArg([string]$Value) {
    return ($Value -replace "\\", "\\\\" -replace "'", "'\\''" -replace " ", "\ ")
}

function Get-UiDump {
    for ($i = 0; $i -lt 3; $i++) {
        $dumpResult = (adb shell uiautomator dump /sdcard/window.xml 2>&1) -join "`n"
        if ($dumpResult -notmatch "ERROR") {
            $xml = (adb shell cat /sdcard/window.xml) -join "`n"
            if ($xml -match "<hierarchy") {
                return $xml
            }
        }
        Start-Sleep -Seconds 1
    }
    return ""
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

function Dismiss-SystemWaitDialog([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    if ($Xml -match "isn.t responding|isn't responding") {
        if ($Xml -match "Beta isn.t responding|Beta isn't responding|com.example.beta") {
            $closePoint = Get-NodeCenterByText "Close app" $Xml
            if ($closePoint) {
                adb shell input tap $closePoint.X $closePoint.Y
                Start-Sleep -Seconds 2
            }
            adb shell am force-stop $Package | Out-Null
            Start-Sleep -Seconds 1
            return Get-UiDump
        }
        if ($Xml -match "Blinkit isn.t responding|Blinkit isn't responding|com.grofers.customerapp") {
            $closePoint = Get-NodeCenterByText "Close app" $Xml
            if ($closePoint) {
                adb shell input tap $closePoint.X $closePoint.Y
                Start-Sleep -Seconds 2
            }
            adb shell am force-stop $BlinkitPackage | Out-Null
            Start-Sleep -Seconds 1
            return Get-UiDump
        }
        $closePoint = Get-NodeCenterByText "Close app" $Xml
        if ($closePoint) {
            adb shell input tap $closePoint.X $closePoint.Y
            Start-Sleep -Seconds 2
            if ($Xml -match "Calendar isn.t responding|Calendar isn't responding|com.google.android.calendar") {
                adb shell am force-stop com.google.android.calendar | Out-Null
            }
            return Get-UiDump
        }
    }
    return $Xml
}

function Get-SavedHomeTapPoint([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    $nodes = [regex]::Matches($Xml, '<node\b[^>]*>')
    foreach ($node in $nodes) {
        $text = [regex]::Match($node.Value, 'text="([^"]*)"')
        $description = [regex]::Match($node.Value, 'content-desc="([^"]*)"')
        $resourceId = [regex]::Match($node.Value, 'resource-id="([^"]*)"')
        $bounds = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($text.Success -and $resourceId.Success -and $bounds.Success `
                -and $text.Groups[1].Value -match "^(?i:home)$" `
                -and $resourceId.Groups[1].Value -eq "com.grofers.customerapp:id/location_title") {
            $titleY = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
            return @{
                X = 540
                Y = [Math]::Min($titleY + 140, 2200)
            }
        }
        if ($description.Success -and $bounds.Success -and $description.Groups[1].Value -match "^(?i:home)$") {
            $titleY = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
            return @{
                X = 540
                Y = [Math]::Min($titleY + 140, 2200)
            }
        }
    }

    return $null
}

function Get-ActiveBlinkitAddressText([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    $parts = @()
    $nodes = [regex]::Matches($Xml, '<node\b[^>]*>')
    foreach ($node in $nodes) {
        $resourceId = [regex]::Match($node.Value, 'resource-id="([^"]*)"')
        if (-not $resourceId.Success) {
            continue
        }
        if ($resourceId.Groups[1].Value -notmatch "^com\.grofers\.customerapp:id/subtitle2(_left_tag)?$") {
            continue
        }
        $bounds = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $bounds.Success -or [int]$bounds.Groups[2].Value -gt 360) {
            continue
        }
        $description = [regex]::Match($node.Value, 'content-desc="([^"]*)"')
        $text = [regex]::Match($node.Value, 'text="([^"]*)"')
        if ($description.Success -and $description.Groups[1].Value.Trim()) {
            $parts += $description.Groups[1].Value
        } elseif ($text.Success -and $text.Groups[1].Value.Trim()) {
            $parts += $text.Groups[1].Value
        }
    }
    return ($parts -join " ")
}

function Test-BlinkitHomeSelected([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    $xml = Dismiss-SystemWaitDialog $Xml
    if ($xml -match "Unserviceable|Select delivery location") {
        return $false
    }
    $activeAddress = Get-ActiveBlinkitAddressText $xml
    if ($activeAddress) {
        if ($activeAddress -match "BHOPAL HOUSE|G B,\s*303|E-2") {
            return $false
        }
        return ($activeAddress -match "HOME\s*-|Jayanagar|Banashankari|Jains Prakriti|602,\s*4th Block")
    }
    return ($xml -match "Delivering to Home|HOME -|Jains Prakriti|602,\s*4th Block")
}

function Test-BlinkitFocused {
    $windowState = (adb shell dumpsys window) -join "`n"
    return ($windowState -match "mCurrentFocus=.*com\.grofers\.customerapp|mFocusedWindow=.*com\.grofers\.customerapp")
}

function Close-BlinkitAddressTip([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    if ($Xml -match "Now share your addresses") {
        adb shell input tap 960 2064
        Start-Sleep -Seconds 1
        return Get-UiDump
    }
    return $Xml
}

function Close-BlinkitReceiverPrompt([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    if ($Xml -match "Location permission not enabled|Select location manually") {
        $manualPoint = Get-NodeCenterByTextOrDesc @("Select location manually") $Xml
        if ($manualPoint) {
            adb shell input tap $manualPoint.X $manualPoint.Y
            Start-Sleep -Seconds 3
            return Get-UiDump
        }
    }
    if ($Xml -match "Ordering for someone else|No, it.?s for me") {
        adb shell input tap 540 2232
        Start-Sleep -Seconds 3
        return Get-UiDump
    }
    return $Xml
}

function Open-BlinkitLocationPicker {
    $tapPoints = @(
        @{ X = 360; Y = 220 },
        @{ X = 645; Y = 277 },
        @{ X = 250; Y = 170 },
        @{ X = 360; Y = 285 }
    )
    foreach ($tapPoint in $tapPoints) {
        adb shell input tap $tapPoint.X $tapPoint.Y
        Start-Sleep -Seconds 2
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
        if ($xml -match "Something went wrong|Please try again later|Try again") {
            $retryPoint = Get-NodeCenterByTextOrDesc @("Try again") $xml
            if ($retryPoint) {
                adb shell input tap $retryPoint.X $retryPoint.Y
                Start-Sleep -Seconds 6
                $xml = Dismiss-SystemWaitDialog (Get-UiDump)
            }
        }
        if ($xml -match "Select delivery location") {
            return $xml
        }
    }
    return Get-UiDump
}

function Wait-SavedHomeTapPoint([int]$TimeoutSeconds = 10) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dismiss-SystemWaitDialog (Close-BlinkitAddressTip)
        $homePoint = Get-SavedHomeTapPoint $xml
        if ($homePoint) {
            return $homePoint
        }
        if (-not $xml) {
            Start-Sleep -Seconds 1
            continue
        }
        if ($xml -notmatch "Select delivery location") {
            return $null
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $null
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

function Reset-EmulatorAnrState {
    Write-Phase "clearing emulator preflight state"
    for ($i = 0; $i -lt 3; $i++) {
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
        if ($xml -notmatch "isn.t responding|isn't responding") {
            break
        }
        Start-Sleep -Seconds 1
    }
    adb shell am force-stop com.google.android.calendar | Out-Null
    adb shell am force-stop $BlinkitPackage | Out-Null
    adb shell am force-stop $Package | Out-Null
    adb shell input keyevent 3 | Out-Null
    Start-Sleep -Seconds 1
    Enable-BetaAccessibility
}

function Start-BlinkitAndWait([int]$TimeoutSeconds = 20) {
    adb shell am start -n "$BlinkitPackage/.DEFAULT" | Out-Null
    Start-Sleep -Seconds 8
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $windowState = (adb shell dumpsys window) -join "`n"
        if ($windowState -match "Application Not Responding: com.grofers.customerapp|Window at fault: com.grofers.customerapp|ANR in com.grofers.customerapp") {
            adb shell input tap 300 1206 | Out-Null
            Start-Sleep -Seconds 1
            adb shell am force-stop $BlinkitPackage | Out-Null
            adb shell am kill $BlinkitPackage | Out-Null
            adb shell input keyevent 3 | Out-Null
            Start-Sleep -Seconds 4
            adb shell am force-stop $BlinkitPackage | Out-Null
            Start-Sleep -Seconds 2
            adb shell am start -n "$BlinkitPackage/.DEFAULT" | Out-Null
            Start-Sleep -Seconds 12
            continue
        }
        if ($windowState -match "mFocusedApp=.*com.grofers.customerapp|mCurrentFocus=.*com.grofers.customerapp") {
            $xml = Get-UiDump
            if ($xml -match 'package="com.grofers.customerapp"') {
                return $xml
            }
            Start-Sleep -Seconds 5
            continue
        }
        adb shell monkey -p $BlinkitPackage -c android.intent.category.LAUNCHER 1 | Out-Null
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw "Blinkit did not become the foreground app."
}

function Test-BetaScreenCaptureServiceRunning {
    $serviceState = (adb shell dumpsys activity services $Package) -join "`n"
    return ($serviceState -match "com.example.beta/.ScreenCaptureService|ScreenCaptureService")
}

function Wait-BetaScreenCaptureReady([int]$TimeoutSeconds = 75) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-BetaScreenCaptureServiceRunning) {
            return $true
        }
        $logs = adb logcat -d | Select-String "Screen capture started successfully|ScreenCaptureService instance set"
        if ($logs -and (Test-BetaScreenCaptureServiceRunning)) {
            return $true
        }
        if (adb logcat -d | Select-String "Media projection failed") {
            return $false
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Start-BetaScreenCapture {
    Write-Phase "launching beta app and starting screen capture"
    adb shell am force-stop $Package | Out-Null
    Start-Sleep -Seconds 1
    adb logcat -c
    adb shell settings put secure accessibility_enabled 0 | Out-Null
    adb shell settings delete secure enabled_accessibility_services | Out-Null
    Start-Sleep -Seconds 1
    adb shell am start -n "$Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 9

    for ($i = 0; $i -lt 3; $i++) {
        $xml = Get-UiDump
        $capturePoint = Get-NodeCenterByTextOrDesc @("START CAPTURE", "button_start_screen_capture") $xml
        if ($capturePoint) {
            adb shell input tap $capturePoint.X $capturePoint.Y
        } else {
            adb shell input tap 540 168
        }

        $deadline = (Get-Date).AddSeconds(90)
        do {
            $permissionXml = Dismiss-SystemWaitDialog (Get-UiDump)
            if ($permissionXml -match "Beta isn.t responding|Beta isn't responding|Application Not Responding: com.example.beta") {
                adb shell am force-stop $Package | Out-Null
                Start-Sleep -Seconds 2
                adb shell am start -n "$Package/.MainActivity" | Out-Null
                Start-Sleep -Seconds 9
                break
            }
            if ($permissionXml -notmatch 'package="com.example.beta"' -and $permissionXml -notmatch "Start now") {
                adb shell am start -n "$Package/.MainActivity" | Out-Null
                Start-Sleep -Seconds 9
                break
            }
            $permissionPoint = Get-NodeCenterByTextOrDesc @("Start now") $permissionXml
            if ($permissionPoint) {
                adb shell input tap $permissionPoint.X $permissionPoint.Y
                if (Wait-BetaScreenCaptureReady 75) {
                    Enable-BetaAccessibility
                    return
                }
                Start-Sleep -Seconds 1
            }
            if (Wait-BetaScreenCaptureReady 2) {
                Enable-BetaAccessibility
                return
            }
            if ((adb logcat -d | Select-String "Media projection failed")) {
                break
            }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $deadline)

        $idleXml = Get-UiDump
        if ($idleXml -match "STATE: IDLE") {
            Start-Sleep -Seconds 2
        }
    }

    adb shell screencap -p /sdcard/beta_ready_failure.png
    adb pull /sdcard/beta_ready_failure.png "$LogsDir\beta_ready_failure.png" | Out-Null
    throw "Beta screen capture did not report readiness."
}

function Select-BlinkitHomeIfNeeded {
    $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    if ($xml -notmatch 'package="com.grofers.customerapp"') {
        adb shell am force-stop com.google.android.calendar | Out-Null
        Start-BlinkitAndWait 45 | Out-Null
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    }
    if ($xml -match "Something went wrong|Please try again later|Try again") {
        $retryPoint = Get-NodeCenterByTextOrDesc @("Try again") $xml
        if ($retryPoint) {
            adb shell input tap $retryPoint.X $retryPoint.Y
            Start-Sleep -Seconds 8
        }
        adb shell am force-stop $BlinkitPackage | Out-Null
        Start-Sleep -Seconds 2
        Start-BlinkitAndWait 45 | Out-Null
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    }
    if (Test-BlinkitHomeSelected $xml) {
        return
    }

    $xml = Open-BlinkitLocationPicker
    if ($xml -match "Select delivery location") {
        $homePoint = Wait-SavedHomeTapPoint 10
        if ($homePoint) {
            $script:BlinkitHomeSelectionAttempted = $true
            adb shell input tap $homePoint.X $homePoint.Y
            $xml = Close-BlinkitReceiverPrompt
        } else {
            throw "Saved Home address was not visible in Blinkit location picker."
        }
    }
    Start-Sleep -Seconds 8
    $xml = Close-BlinkitReceiverPrompt

    $xml = Get-UiDump
    if (-not (Test-BlinkitHomeSelected $xml)) {
        $xml = Open-BlinkitLocationPicker
        $homePoint = Wait-SavedHomeTapPoint 10
        if ($homePoint) {
            $script:BlinkitHomeSelectionAttempted = $true
            adb shell input tap $homePoint.X $homePoint.Y
            $xml = Close-BlinkitReceiverPrompt
        } else {
            throw "Saved Home address was not visible in Blinkit location picker."
        }
        Start-Sleep -Seconds 8
        $xml = Close-BlinkitReceiverPrompt
    }

    $xml = Get-UiDump
    if (-not (Test-BlinkitHomeSelected $xml)) {
        if (-not $xml -and $script:BlinkitHomeSelectionAttempted -and (Test-BlinkitFocused)) {
            return
        }
        throw "Blinkit did not switch to saved Home address."
    }
}

function Ensure-BlinkitHomeScreen {
    for ($i = 0; $i -lt 5; $i++) {
        $windowState = (adb shell dumpsys window) -join "`n"
        if ($windowState -match "mCurrentFocus=.*Application Not Responding: com.grofers.customerapp|mFocusedWindow=.*Application Not Responding: com.grofers.customerapp") {
            adb shell input tap 300 1206 | Out-Null
            Start-Sleep -Seconds 1
            adb shell am force-stop $BlinkitPackage | Out-Null
            Start-Sleep -Seconds 3
            Start-BlinkitAndWait 45 | Out-Null
            Select-BlinkitHomeIfNeeded
            Start-Sleep -Seconds 3
            continue
        }
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
        if (-not $xml -and $script:BlinkitHomeSelectionAttempted -and (Test-BlinkitFocused)) {
            return
        }
        if ($xml -notmatch 'package="com.grofers.customerapp"') {
            adb shell am force-stop com.google.android.calendar | Out-Null
            Start-BlinkitAndWait 45 | Out-Null
            Start-Sleep -Seconds 3
            continue
        }
        if ($xml -match "Something went wrong|Please try again later|Try again") {
            $retryPoint = Get-NodeCenterByTextOrDesc @("Try again") $xml
            if ($retryPoint) {
                adb shell input tap $retryPoint.X $retryPoint.Y
                Start-Sleep -Seconds 8
            }
            adb shell am force-stop $BlinkitPackage | Out-Null
            Start-Sleep -Seconds 2
            Start-BlinkitAndWait 45 | Out-Null
            continue
        }
        if ($xml -match "Unserviceable area|Santa Clara County|Mountain View|BHOPAL HOUSE|Bhopal House") {
            Select-BlinkitHomeIfNeeded
            Start-Sleep -Seconds 3
            continue
        }
        $onHome = (Test-BlinkitHomeSelected $xml) -and ($xml -match "Search for atta|Search for") -and ($xml -notmatch "Filters|Sort|Search across filters")
        if ($onHome) {
            return
        }
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 2
    }

    adb shell monkey -p $BlinkitPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 4
    $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    if (-not $xml -and $script:BlinkitHomeSelectionAttempted -and (Test-BlinkitFocused)) {
        return
    }
    if ((Test-BlinkitHomeSelected $xml) -and ($xml -match "Search for atta|Search for") -and ($xml -notmatch "Filters|Sort|Search across filters")) {
        return
    }
    throw "Blinkit did not return to the Home search screen."
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

function Wait-ForFlowOutcome([int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $logs = adb logcat -d | Select-String "BLINKIT_CART_INCREMENT_CONFIRMED|FLOW_SUCCESS|STATE: SUCCESS|cart increment confirmed|Item found in cart: true|FLOW_FAILED|STATE: FAILED"
        if ($logs) {
            $text = ($logs | Select-Object -Last 20) -join "`n"
            if ($text -match "BLINKIT_CART_INCREMENT_CONFIRMED|FLOW_SUCCESS|STATE: SUCCESS|cart increment confirmed|Item found in cart: true") {
                return "success"
            }
            if ($text -match "FLOW_FAILED|STATE: FAILED") {
                return "failed"
            }
        }
        if (Test-BackendCartVerified) {
            return "success"
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return "timeout"
}

function Clear-BackendDebugArtifacts {
    try {
        curl.exe -k -s -X DELETE "https://127.0.0.1:8000/debug-artifacts" | Out-Null
    } catch {
        Write-Warning "Could not clear backend debug artifacts: $($_.Exception.Message)"
    }
}

function Write-Phase([string]$Message) {
    Write-Host "[$ScenarioName] $Message"
}

function Reset-BlinkitCart {
    if ($SkipCartReset) {
        return
    }

    try {
        adb shell input keyevent 111 | Out-Null
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 500
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 500
        $xml = Get-UiDump
        $viewCart = Get-NodeCenterByTextOrDesc @("View cart", "View Cart", "Go to cart", "View items in cart") $xml
        if ($viewCart) {
            adb shell input tap $viewCart.X $viewCart.Y
            Start-Sleep -Seconds 3
        }

        for ($i = 0; $i -lt 8; $i++) {
            $xml = Get-UiDump
            $control = Get-NodeCenterByTextOrDesc @("Decrease quantity", "Remove", "Delete", "−", "–", "-") $xml
            if (-not $control) {
                break
            }
            adb shell input tap $control.X $control.Y
            Start-Sleep -Seconds 1
        }

        adb shell input keyevent 3 | Out-Null
    } catch {
        Write-Warning "Cart reset attempt failed: $($_.Exception.Message)"
    }
}

function Set-TextByClipboard([string]$Text) {
    $escaped = $Text.Replace("'", "''")
    adb shell cmd clipboard set text "$escaped" | Out-Null
    Start-Sleep -Milliseconds 300
    adb shell input keyevent 279
}

Require-Device
Require-Package $BlinkitPackage

Clear-BackendDebugArtifacts

if (-not $SkipBuild) {
    .\gradlew.bat --no-daemon assembleDebug assembleDebugAndroidTest -x startLogcatCapture
    .\gradlew.bat --no-daemon installDebug -x startLogcatCapture
}

adb shell appops set $Package SYSTEM_ALERT_WINDOW allow | Out-Null

adb shell settings put secure accessibility_enabled 0
adb shell settings delete secure enabled_accessibility_services | Out-Null
Start-Sleep -Seconds 2
Enable-BetaAccessibility

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb logcat -c

$result = 1
$instructionSubmitted = $false

try {
    Reset-EmulatorAnrState

    Write-Phase "launching Blinkit and selecting saved Home address"
    adb shell am force-stop $BlinkitPackage | Out-Null
    Start-Sleep -Seconds 1
    Start-BlinkitAndWait 90 | Out-Null
    Select-BlinkitHomeIfNeeded
    Ensure-BlinkitHomeScreen

    Start-BetaScreenCapture
    Start-BlinkitAndWait 90 | Out-Null
    Ensure-BlinkitHomeScreen

    Write-Phase "submitting beta emulator instruction"
    if (-not (Test-BetaScreenCaptureServiceRunning)) {
        Start-BetaScreenCapture
        Start-BlinkitAndWait 90 | Out-Null
        Ensure-BlinkitHomeScreen
    }
    Enable-BetaAccessibility
    Wait-BetaAccessibilityConnected 15 | Out-Null
    $escapedInstruction = ConvertTo-AdbShellArg $Instruction
    adb shell "am broadcast -n $Package/.AutomationInstructionReceiver -a com.example.beta.SUBMIT_AUTOMATION_INSTRUCTION --es instruction $escapedInstruction" | Out-Null
    $instructionSubmitted = $true
    adb shell monkey -p $BlinkitPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 1

    if (-not (Wait-ForLog "INSTRUCTION_RECEIVED|BLINKIT_SEARCH_STARTED" 20)) {
        throw "Beta did not receive the emulator instruction."
    }

    Write-Phase "waiting for safe add-to-cart verification"
    $outcome = Wait-ForFlowOutcome 240
    if ($outcome -ne "success") {
        Write-Warning "Blinkit flow outcome for '$Instruction': $outcome"
        $result = 1
    } else {
        $result = 0
    }
} finally {
    adb logcat -d AndroidRuntime:E "*:S" > (Join-Path $LogsDir "$ScenarioName`_crash_log.txt")
    adb logcat -d | Select-String "$Package|BetaAgent|AndroidRuntime|FATAL EXCEPTION|Accessibility|MediaProjection|Blinkit|grofers|Cart|$Instruction" > (Join-Path $LogsDir "$ScenarioName`_full_log.txt")
    adb exec-out screencap -p > (Join-Path $LogsDir "$ScenarioName`_final_screen.png")
    if ($instructionSubmitted) {
        Write-Phase "resetting Blinkit cart after submitted instruction"
        Reset-BlinkitCart
    } else {
        Write-Phase "skipping cart reset because no instruction was submitted"
    }
}

if ($result -ne 0) {
    throw "Blinkit flow test failed for '$Instruction'. See logs\$ScenarioName`_full_log.txt and logs\$ScenarioName`_crash_log.txt."
}

Write-Host "Blinkit flow test passed for '$Instruction' without checkout/payment."
