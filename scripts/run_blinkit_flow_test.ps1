param(
    [string]$Instruction = "order butter",
    [switch]$SkipBuild,
    [switch]$SkipCartReset
)

$ErrorActionPreference = "Stop"

$Package = "com.example.beta"
$Service = "com.example.beta/com.example.beta.MyAccessibilityService"
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
$script:BlinkitHomeConfirmed = $false
$script:BetaCaptureStarted = $false
$script:BlinkitAddToCartClicked = $false

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
    return ($Value -replace "\\", "\\\\" -replace "'", "\'" -replace " ", "\ ")
}

function Get-UiDump {
    [CmdletBinding()]
    param(
        [switch]$SkipAnrDismiss
    )

    for ($i = 0; $i -lt 3; $i++) {
        if (-not $SkipAnrDismiss -and (Dismiss-AnrFromWindowState)) {
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

function Get-CurrentWindowFocusLines {
    $windowState = (adb shell dumpsys window) -join "`n"
    $focusLines = @{}
    foreach ($line in ($windowState -split "`n")) {
        if ($line -match "mCurrentFocus=") {
            $focusLines["mCurrentFocus"] = $line
        } elseif ($line -match "mFocusedWindow=") {
            $focusLines["mFocusedWindow"] = $line
        } elseif ($line -match "mTopFullscreenOpaqueWindow=") {
            $focusLines["mTopFullscreenOpaqueWindow"] = $line
        } elseif ($line -match "mTopFullscreenOpaqueWindowState=") {
            $focusLines["mTopFullscreenOpaqueWindowState"] = $line
        }
    }
    $lines = @()
    if ($focusLines.ContainsKey("mCurrentFocus")) { $lines += $focusLines["mCurrentFocus"] }
    if ($focusLines.ContainsKey("mFocusedWindow")) { $lines += $focusLines["mFocusedWindow"] }
    if ($focusLines.ContainsKey("mTopFullscreenOpaqueWindow")) { $lines += $focusLines["mTopFullscreenOpaqueWindow"] }
    if ($focusLines.ContainsKey("mTopFullscreenOpaqueWindowState")) { $lines += $focusLines["mTopFullscreenOpaqueWindowState"] }
    return $lines
}

function Dismiss-AnrFromWindowState {
    [CmdletBinding()]
    param(
        [switch]$WaitOnly
    )

    $windowState = (adb shell dumpsys window) -join "`n"
    $focusStates = Get-CurrentWindowFocusLines
    $focusText = $focusStates -join "`n"
    if ($focusText -notmatch "(?i)Application Not Responding:") {
        return $false
    }

    $blinkitAnrLine = $focusStates | Where-Object { $_ -match "(?i)Application Not Responding:\s*com\.grofers\.customerapp" }
    $betaAnrLine = $focusStates | Where-Object { $_ -match "(?i)Application Not Responding:\s*com\.example\.beta" }
    $calendarAnrLine = $focusStates | Where-Object { $_ -match "(?i)Application Not Responding:\s*com\.google\.android\.calendar" }
    $systemAnrLine = $focusStates | Where-Object { $_ -match "(?i)Application Not Responding:\s*(system|com\.android\.systemui)" }
    $anrCloseX = 348
    $anrCloseY = 1206
    $anrWaitX = 348
    $anrWaitY = 1332
    $anrSystemCloseX = 280
    $anrSystemCloseY = 1206

    if ($blinkitAnrLine) {
        if ($WaitOnly) {
            adb shell input tap $anrWaitX $anrWaitY | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
        adb shell input tap $anrCloseX $anrCloseY | Out-Null
        adb shell am force-stop $BlinkitPackage | Out-Null
        $script:BlinkitHomeConfirmed = $false
        Start-Sleep -Seconds 2
        return $true
    }
    if ($betaAnrLine) {
        if ($WaitOnly) {
            adb shell input tap $anrWaitX $anrWaitY | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
        adb shell input tap $anrCloseX $anrCloseY | Out-Null
        adb shell am force-stop $Package | Out-Null
        Start-Sleep -Seconds 2
        return $true
    }
    if ($calendarAnrLine) {
        if ($WaitOnly) {
            adb shell input tap $anrWaitX $anrWaitY | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
        adb shell am force-stop com.google.android.calendar | Out-Null
        Start-Sleep -Seconds 2
        return $true
    }
    if ($systemAnrLine) {
        if ($WaitOnly) {
            adb shell input tap $anrWaitX $anrWaitY | Out-Null
            Start-Sleep -Seconds 2
            return $true
        }
        adb shell input tap $anrSystemCloseX $anrSystemCloseY | Out-Null
        if (($focusText -match "com\.grofers\.customerapp") -or (Get-TopActivityState -match "com\.grofers\.customerapp")) {
            adb shell am force-stop $BlinkitPackage | Out-Null
            $script:BlinkitHomeConfirmed = $false
            Start-Sleep -Seconds 2
            return $true
        }
        Start-Sleep -Seconds 2
        return $true
    }
    return $false
}

function Dismiss-FullScreenEducation([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    if ($Xml -match "Viewing full screen|To exit, swipe down from the top|Got it") {
        $gotItPoint = Get-NodeCenterByTextOrDesc @("Got it") $Xml
        if ($gotItPoint) {
            adb shell input tap $gotItPoint.X $gotItPoint.Y
        } else {
            adb shell input tap 856 530
        }
        Start-Sleep -Seconds 1
        return Get-UiDump
    }
    return $Xml
}

function Get-SavedHomeTapPoint([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    $nodes = [regex]::Matches($Xml, '<node\b[^>]*>')
    $best = $null

    foreach ($node in $nodes) {
        $text = [regex]::Match($node.Value, 'text="([^"]*)"')
        $description = [regex]::Match($node.Value, 'content-desc="([^"]*)"')
        $resourceId = [regex]::Match($node.Value, 'resource-id="([^"]*)"')
        $bounds = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $bounds.Success) {
            continue
        }

        $textValue = if ($text.Success) { $text.Groups[1].Value } else { "" }
        $descriptionValue = if ($description.Success) { $description.Groups[1].Value } else { "" }
        $resourceValue = if ($resourceId.Success) { $resourceId.Groups[1].Value } else { "" }
        $left = [int]$bounds.Groups[1].Value
        $top = [int]$bounds.Groups[2].Value
        $right = [int]$bounds.Groups[3].Value
        $bottom = [int]$bounds.Groups[4].Value

        $cat = -1
        if ($textValue -match '^(?i)home$') {
            $cat = if ($resourceValue -match 'location_title$') { 0 } else { 1 }
        } elseif ($descriptionValue -eq "Home") {
            $cat = 2
        } elseif ($textValue -match '^(?i)home\s*-') {
            $cat = 3
        }

        if ($cat -lt 0) {
            continue
        }

        $candidate = @{
            Cat   = $cat
            Top   = $top
            Left  = $left
            Right = $right
            Bottom = $bottom
        }
        if (-not $best -or $cat -lt $best.Cat -or ($cat -eq $best.Cat -and $top -lt $best.Top)) {
            $best = $candidate
        }
    }

    if (-not $best) {
        return $null
    }

    return @{
        X = [int](($best.Left + $best.Right) / 2)
        Y = [int](($best.Top + $best.Bottom) / 2)
    }
}

function Save-BlinkitHomeRecoveryArtifact([string]$Reason) {
    try {
        $stamp = Get-Date -Format "yyyyMMdd_HHmmssfff"
        $safeReason = $Reason -replace "[^A-Za-z0-9_-]", "_"
        $path = Join-Path $LogsDir "home_recovery_${safeReason}_$stamp.png"
        adb exec-out screencap -p > $path
        Write-Phase "[home-recovery] $Reason | screenshot: $path"
    } catch {
        Write-Warning "[home-recovery] screenshot capture failed: $($_.Exception.Message)"
    }
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
    $isSavedHome = "(?i)Jayanagar|Bangalore|7th\\s+Block|4th\\s+Block|Jains\\s+Prakriti|KR\\s+Road|\\b602\\b"
    if ($xml -match "Unserviceable|Select delivery location|Choose delivery location|Search for area|Your saved addresses|Use current location|Add new address|Santa Clara|Mountain View") {
        return $false
    }
    $hasSearchBar = $xml -match "Search for atta|Search for|Blinkit in"
    if (-not $hasSearchBar) {
        return $false
    }

    $activeAddress = Get-ActiveBlinkitAddressText $xml
    if ($activeAddress -and ($activeAddress -match "BHOPAL HOUSE|G B,\s*303|E-2")) {
        return $false
    }

    if ($activeAddress -and ($activeAddress -match '^(?i)home\s*-')) {
        return $true
    }
    if ($activeAddress -and ($activeAddress -match $isSavedHome)) {
        return $true
    }

    $nodes = [regex]::Matches($xml, '<node\b[^>]*>')
    foreach ($node in $nodes) {
        $bounds = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $bounds.Success -or [int]$bounds.Groups[2].Value -ge 360) {
            continue
        }
        $text = [regex]::Match($node.Value, 'text="([^"]*)"')
        $description = [regex]::Match($node.Value, 'content-desc="([^"]*)"')
        $textValue = if ($text.Success) { $text.Groups[1].Value } else { "" }
        $descriptionValue = if ($description.Success) { $description.Groups[1].Value } else { "" }
        if ($descriptionValue -eq "Home" -or $textValue -eq "HOME") {
            return $true
        }
    }

    if (-not $activeAddress -and ($xml -match "Search for atta|Search for") -and ($xml -notmatch "Unserviceable|Select delivery location")) {
        return $true
    }

    return $false
}

function Get-TopActivityState {
    return ((adb shell dumpsys activity activities) | Select-String "topResumedActivity|ResumedActivity|mFocusedApp" | Select-Object -First 6) -join "`n"
}

function Test-BlinkitFocused {
    $windowState = (adb shell dumpsys window) -join "`n"
    $topActivityState = Get-TopActivityState
    $focusStates = $windowState -split "`n" | Where-Object {
        $_ -match "mFocusedWindow=|mCurrentFocus=|mTopFullscreenOpaqueWindow="
    }
    $launcherStates = "com\.google\.android\.apps\.nexuslauncher|com\.example\.beta|com\.google\.android\.calendar"

    if ($topActivityState -match $launcherStates) {
        return $false
    }
    if ($topActivityState -match "com\.grofers\.customerapp") {
        return $true
    }
    if ($focusStates | Where-Object { $_ -match $launcherStates }) {
        return $false
    }
    if ($focusStates | Where-Object { $_ -match "com\.grofers\.customerapp" }) {
        return $true
    }

    $visibleUi = Get-UiDump -SkipAnrDismiss
    if ($visibleUi -match 'package="com.grofers.customerapp"') {
        return $true
    }

    if ($visibleUi -match 'package="com.google.android.apps.nexuslauncher"|package="com.example.beta"|package="com.google.android.calendar"') {
        return $false
    }

    return $false
}

function Test-BlinkitTopNoDump {
    [CmdletBinding()]
    param(
        [switch]$RequireHomeActivity
    )

    $windowState = (adb shell dumpsys window) -join "`n"
    $topActivityState = Get-TopActivityState
    $focusStates = Get-CurrentWindowFocusLines
    $launcherStates = "com\.google\.android\.apps\.nexuslauncher|com\.example\.beta|com\.google\.android\.calendar"
    $blinkitStates = "com\.grofers\.customerapp"
    $homeActivityState = 'com\.grofers\.customerapp/(?:\.?DEFAULT|[^ "]*HomeActivity)'
    $focusText = ($focusStates -join "`n")

    if ($topActivityState -match "(?i)Application Not Responding:" -or
            $focusText -match "(?i)Application Not Responding:") {
        return $false
    }

    if ($topActivityState -match $launcherStates) {
        return $false
    }
    if ($topActivityState -match $blinkitStates) {
        if ($RequireHomeActivity) {
            if ($topActivityState -match $homeActivityState) {
                return $true
            }
            return $false
        } else {
            return $true
        }
    }

    if ($focusText -match $launcherStates) {
        return $false
    }
    if ($focusText -match $blinkitStates) {
        if ($RequireHomeActivity) {
            if ($focusText -match $homeActivityState) {
                return $true
            }
            return $false
        }
        return $true
    }

    if ($windowState -notmatch $blinkitStates) {
        return $false
    }
    if (-not $RequireHomeActivity -or $windowState -match $homeActivityState) {
        return $true
    }

    return $false
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
    $xml = Dismiss-SystemWaitDialog (Close-BlinkitAddressTip)
    if ($xml -match "Select delivery location|Choose delivery location") {
        return $xml
    }

    $premXml = Dismiss-SystemWaitDialog (Get-UiDump)
    $activeAddr = (Get-ActiveBlinkitAddressText $premXml).Trim()
    $chipNodes = [regex]::Matches($premXml, '<node\b[^>]*>')
    $bestChip = $null
    foreach ($node in $chipNodes) {
        if ($node.Value -notmatch 'clickable="true"') {
            continue
        }
        $textM = [regex]::Match($node.Value, 'text="([^"]*)"')
        $descM = [regex]::Match($node.Value, 'content-desc="([^"]*)"')
        $bounds = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $bounds.Success) {
            continue
        }
        $textValue = if ($textM.Success) { $textM.Groups[1].Value } else { "" }
        $descValue = if ($descM.Success) { $descM.Groups[1].Value } else { "" }
        $hay = "$textValue $descValue"
        $matchesChip = $false
        if ($hay -match 'Blinkit in\s*\d+\s*min(?:utes)?') {
            $matchesChip = $true
        } elseif ($activeAddr -and $hay -match [regex]::Escape($activeAddr)) {
            $matchesChip = $true
        }
        if (-not $matchesChip) {
            continue
        }
        $top = [int]$bounds.Groups[2].Value
        $left = [int]$bounds.Groups[1].Value
        $right = [int]$bounds.Groups[3].Value
        $bottom = [int]$bounds.Groups[4].Value
        if (-not $bestChip -or $top -lt $bestChip.Top) {
            $bestChip = @{
                Top = $top
                X = [int](($left + $right) / 2)
                Y = [int](($top + $bottom) / 2)
            }
        }
    }

    $tapPoints = @(
        @{ X = 360; Y = 220 },
        @{ X = 645; Y = 277 },
        @{ X = 250; Y = 170 },
        @{ X = 360; Y = 285 }
    )

    if ($bestChip) {
        adb shell input tap $bestChip.X $bestChip.Y
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
        $pickerOpen = $xml -match "Select delivery location" -or $xml -match "Saved addresses" `
            -or $xml -match "Choose your delivery location" `
            -or ($xml -match 'text="Home"' -and $xml -match 'text="Work"')
        if ($pickerOpen) {
            return $xml
        }
    }

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
        $pickerOpen = $xml -match "Select delivery location" -or $xml -match "Saved addresses" `
            -or $xml -match "Choose your delivery location" `
            -or ($xml -match 'text="Home"' -and $xml -match 'text="Work"')
        if ($pickerOpen) {
            return $xml
        }
    }
    return Get-UiDump
}

function Wait-BlinkitLocationUiReady([int]$TimeoutSeconds = 8) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
        if ($xml -match "Unserviceable area|subtitle2|HOME -|Blinkit in|Search for atta|Select delivery location") {
            return $xml
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $xml
}

function Wait-BlinkitHomeMainScreen([int]$TimeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
        if (Test-BlinkitHomeSelected $xml) {
            $script:BlinkitHomeConfirmed = $true
            return $xml
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Close-BlinkitLocationPickerIfOpen([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    if ($Xml -match "Select delivery location|Choose delivery location|Your saved addresses|Search for area") {
        $closePoint = Get-NodeCenterByTextOrDesc @("Close") $Xml
        if ($closePoint) {
            adb shell input tap $closePoint.X $closePoint.Y
        } else {
            adb shell input keyevent 4 | Out-Null
        }
        Start-Sleep -Seconds 3
        return Get-UiDump
    }
    return $Xml
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

function Wait-ForUiPattern([string]$Pattern, [int]$TimeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Dismiss-AnrFromWindowState) {
            continue
        }
        $xml = Dismiss-SystemWaitDialog (Get-UiDump)
        if ($xml -match $Pattern) {
            return $xml
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $xml
}

function Set-EmulatorNearHomeLocation {
    try {
        adb shell cmd location set-location-enabled true | Out-Null
        adb shell appops set 2000 android:mock_location allow | Out-Null
        adb shell cmd location providers add-test-provider gps --supportsAltitude --supportsSpeed --supportsBearing 2>$null | Out-Null
        adb shell cmd location providers set-test-provider-enabled gps true | Out-Null
        adb shell cmd location providers set-test-provider-location gps --location 12.9255,77.5738 --accuracy 20 | Out-Null
        Write-Phase "[home] set mock location to Jayanagar home area (12.9255,77.5738)"
    } catch {
        Write-Warning "[home] could not set emulator test location: $($_.Exception.Message)"
    }
}

function Set-BlinkitHomeFromAddressBook {
    Write-Phase "[home] selecting Home through profile address book"
    adb shell am force-stop com.google.android.calendar | Out-Null
    Start-BlinkitAndWait 45 | Out-Null
    $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    if ($xml -notmatch 'package="com.grofers.customerapp"') {
        Save-BlinkitHomeRecoveryArtifact "address_book_blinkit_not_foreground"
    }
    $profilePoint = Get-NodeCenterByTextOrDesc @("Go to profile") $xml
    if ($profilePoint) {
        adb shell input tap $profilePoint.X $profilePoint.Y
    } else {
        adb shell input tap 1000 183
    }

    $xml = Wait-ForUiPattern "Your account|Address book" 60
    if ($xml -notmatch "Address book") {
        Save-BlinkitHomeRecoveryArtifact "address_book_entry_not_visible"
        return $false
    }

    $addressBookPoint = Get-NodeCenterByTextOrDesc @("Address book") $xml
    if (-not $addressBookPoint) {
        Save-BlinkitHomeRecoveryArtifact "address_book_tap_point_missing"
        return $false
    }
    adb shell input tap $addressBookPoint.X $addressBookPoint.Y

    $xml = Wait-ForUiPattern "My addresses|Your saved addresses|Home" 45
    if ($xml -notmatch "Your saved addresses|Home") {
        Save-BlinkitHomeRecoveryArtifact "address_book_home_not_visible"
        return $false
    }

    $homePoint = Get-SavedHomeTapPoint $xml
    if (-not $homePoint) {
        Save-BlinkitHomeRecoveryArtifact "address_book_home_tap_point_missing"
        return $false
    }
    adb shell input tap $homePoint.X $homePoint.Y

    $xml = Wait-ForUiPattern "Set as delivery address|Select option" 15
    $setPoint = Get-NodeCenterByTextOrDesc @("Set as delivery address") $xml
    if (-not $setPoint) {
        Save-BlinkitHomeRecoveryArtifact "set_delivery_address_option_missing"
        return $false
    }
    adb shell input tap $setPoint.X $setPoint.Y

    Start-Sleep -Seconds 12
    Start-BlinkitAndWait 45 | Out-Null
    $xml = Wait-BlinkitHomeMainScreen 45
    if (Test-BlinkitHomeSelected $xml) {
        $script:BlinkitHomeConfirmed = $true
        return $true
    }
    Save-BlinkitHomeRecoveryArtifact "address_book_home_set_failed"
    return $false
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
    # Dismiss stale dialogs deterministically without touching UI dumps.
    for ($preflightDismissAttempts = 0; $preflightDismissAttempts -lt 2; $preflightDismissAttempts++) {
        adb shell input tap 203 1518 | Out-Null
        Start-Sleep -Milliseconds 250
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Milliseconds 250
    }
    adb shell input tap 280 1206 | Out-Null
    Start-Sleep -Milliseconds 250
    adb shell am force-stop com.google.android.calendar | Out-Null
    adb shell am force-stop $BlinkitPackage | Out-Null
    adb shell am force-stop $Package | Out-Null
    $script:BlinkitHomeConfirmed = $false
    $script:BetaCaptureStarted = $false
    adb shell input keyevent 3 | Out-Null
    Start-Sleep -Milliseconds 800
    Enable-BetaAccessibility
}

function Start-BlinkitAndWait([int]$TimeoutSeconds = 20) {
    adb shell am start -n "$BlinkitPackage/.DEFAULT" | Out-Null
    Start-Sleep -Seconds 8
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $blinkitForegroundFailCount = 0
    do {
        $windowState = (adb shell dumpsys window) -join "`n"
        $focusState = (($windowState -split "`n") | Where-Object { $_ -match "mCurrentFocus=|mFocusedWindow=" } | Select-Object -Last 2) -join "`n"
        if (Dismiss-AnrFromWindowState) {
            adb shell input keyevent 3 | Out-Null
            Start-Sleep -Seconds 2
            adb shell am start -n "$BlinkitPackage/.DEFAULT" | Out-Null
            Start-Sleep -Seconds 8
            $blinkitForegroundFailCount = 0
            continue
        }
        if ($windowState -match "ImmersiveModeConfirmation") {
            Dismiss-FullScreenEducation (Get-UiDump) | Out-Null
            adb shell input tap 856 530 | Out-Null
            Start-Sleep -Seconds 1
            $blinkitForegroundFailCount = 0
            continue
        }
        if (Test-BlinkitTopNoDump) {
            $blinkitForegroundFailCount = 0
            return ""
        }
        $blinkitForegroundFailCount++
        if ($blinkitForegroundFailCount -ge 2) {
            adb shell am force-stop $BlinkitPackage | Out-Null
            $script:BlinkitHomeConfirmed = $false
            Start-Sleep -Milliseconds 800
            adb shell am start -n "$BlinkitPackage/.DEFAULT" | Out-Null
            Start-Sleep -Seconds 2
            $blinkitForegroundFailCount = 0
            continue
        }
        adb shell monkey -p $BlinkitPackage -c android.intent.category.LAUNCHER 1 | Out-Null
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    Start-Sleep -Seconds 1
    if (Test-BlinkitTopNoDump) {
        return ""
    }
    throw "Blinkit did not become the foreground app."
}

function Select-BlinkitHomeByVisibleCoordinates {
    if (-not (Test-BlinkitTopNoDump)) {
        return $false
    }

    Write-Phase "[home] using no-dump coordinate-first Home selection"
    adb shell input tap 357 277 | Out-Null
    Start-Sleep -Seconds 11
    adb shell input tap 278 1426 | Out-Null

    $deadline = (Get-Date).AddSeconds(35)
    do {
        if (Test-BlinkitTopNoDump) {
            $script:BlinkitHomeConfirmed = $true
            return $true
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    Write-Phase "[home] no-dump coordinate-first Home path did not confirm HOME activity"
    return Select-BlinkitHomeByVisibleCoordinatesLegacy
}

function Select-BlinkitHomeByVisibleCoordinatesLegacy {
    if (-not (Test-BlinkitFocused)) {
        return $false
    }

    Write-Phase "[home] using visible-coordinate fallback for saved Home"
    adb shell input tap 357 277 | Out-Null
    $homePoint = Wait-SavedHomeTapPoint 15
    if ($homePoint) {
        adb shell input tap $homePoint.X $homePoint.Y | Out-Null
    } else {
        adb shell input tap 540 1500 | Out-Null
    }
    Start-Sleep -Seconds 10

    $xml = Wait-BlinkitLocationUiReady 8
    if (Test-BlinkitHomeSelected $xml) {
        $script:BlinkitHomeConfirmed = $true
        return $true
    }
    Save-BlinkitHomeRecoveryArtifact "coordinate_home_fallback_failed"
    return $false
}

function Test-BetaScreenCaptureServiceRunning {
    $serviceState = (adb shell dumpsys activity services $Package) -join "`n"
    return ($serviceState -match "com.example.beta/.ScreenCaptureService|ScreenCaptureService")
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

        $xml = Get-UiDump
        $startNowPoint = Get-NodeCenterByTextOrDesc @("Start now", "Start Now") $xml
        if ($startNowPoint) {
            adb shell input tap $startNowPoint.X $startNowPoint.Y | Out-Null
        } else {
            adb shell input tap 854 1530 | Out-Null
        }
        Start-Sleep -Seconds 1
        return $true
    } while ((Get-Date) -lt $deadline)
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
    if (Dismiss-AnrFromWindowState) {
        adb shell am start -n "$Package/.MainActivity" | Out-Null
        Start-Sleep -Milliseconds 900
    }

    for ($i = 0; $i -lt 3; $i++) {
        if (Dismiss-AnrFromWindowState) {
            adb shell am start -n "$Package/.MainActivity" | Out-Null
            Start-Sleep -Milliseconds 900
            continue
        }
        adb shell input tap 540 168
        Start-Sleep -Seconds 2
        if (Dismiss-AnrFromWindowState) {
            adb shell am start -n "$Package/.MainActivity" | Out-Null
            Start-Sleep -Milliseconds 900
            continue
        }
        Tap-MediaProjectionPermissionDialog
        Start-Sleep -Seconds 1
        if (Dismiss-AnrFromWindowState) {
            adb shell am start -n "$Package/.MainActivity" | Out-Null
            Start-Sleep -Milliseconds 900
            continue
        }

        if (Wait-BetaScreenCaptureReady 3) {
            Enable-BetaAccessibility
            $script:BetaCaptureStarted = $true
            adb shell am broadcast -a "$Package.HIDE_AUTOMATION_OVERLAY" -p $Package | Out-Null
            adb shell input keyevent 3 | Out-Null
            Start-Sleep -Seconds 1
            return
        }

        if (Wait-BetaScreenCaptureReady 24) {
            Enable-BetaAccessibility
            $script:BetaCaptureStarted = $true
            adb shell am broadcast -a "$Package.HIDE_AUTOMATION_OVERLAY" -p $Package | Out-Null
            adb shell input keyevent 3 | Out-Null
            Start-Sleep -Seconds 1
            return
        }

        if ((adb logcat -d | Select-String "Beta isn.t responding|Beta isn't responding|Application Not Responding: com.example.beta")) {
            adb shell am force-stop $Package | Out-Null
            Start-Sleep -Seconds 2
            adb shell am start -n "$Package/.MainActivity" | Out-Null
            Start-Sleep -Seconds 9
            continue
        }

        if ((adb logcat -d | Select-String "Media projection failed")) {
            if ((Test-BetaScreenCaptureServiceRunning) -and
                    (adb logcat -d | Select-String "Screen capture started successfully|Screen capture started successfully after retry|VirtualDisplay created successfully|VirtualDisplay with minimal flags")) {
                Enable-BetaAccessibility
                $script:BetaCaptureStarted = $true
                adb shell am broadcast -a "$Package.HIDE_AUTOMATION_OVERLAY" -p $Package | Out-Null
                adb shell input keyevent 3 | Out-Null
                Start-Sleep -Seconds 1
                return
            }
            continue
        }

        Start-Sleep -Seconds 1
    }

    adb shell screencap -p /sdcard/beta_ready_failure.png
    adb pull /sdcard/beta_ready_failure.png "$LogsDir\beta_ready_failure.png" | Out-Null
    throw "Beta screen capture did not report readiness."
}

function Select-BlinkitHomeIfNeeded {
    Write-Phase "[home] ensuring saved Home is selected"

    if (-not (Test-BlinkitTopNoDump)) {
        adb shell am force-stop com.google.android.calendar | Out-Null
        Start-BlinkitAndWait 45 | Out-Null
    }
    if (Select-BlinkitHomeByVisibleCoordinates) {
        return
    }

    $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    if ($xml -notmatch 'package="com.grofers.customerapp"') {
        if (Select-BlinkitHomeByVisibleCoordinates) {
            return
        }
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
    $xml = Wait-BlinkitLocationUiReady 10
    if (-not $xml -or $xml -notmatch "Unserviceable area|subtitle2|HOME -|Blinkit in|Search for atta|Select delivery location") {
        Save-BlinkitHomeRecoveryArtifact "home_ui_not_ready_before_selection"
        if (Select-BlinkitHomeByVisibleCoordinates) {
            return
        }
        if (Set-BlinkitHomeFromAddressBook) {
            return
        }
        throw "Blinkit home UI was not ready for location selection."
    }
    if (Test-BlinkitHomeSelected $xml) {
        Write-Phase "[home] already on saved Home"
        $script:BlinkitHomeConfirmed = $true
        return
    }
    if (Select-BlinkitHomeByVisibleCoordinates) {
        return
    }
    if (Set-BlinkitHomeFromAddressBook) {
        return
    }

    $xml = Open-BlinkitLocationPicker
    if ($xml -notmatch "Select delivery location") {
        Start-Sleep -Seconds 1
        $xml = Wait-BlinkitLocationUiReady 4
    }
    if ($xml -notmatch "Select delivery location") {
        Write-Phase "[home] retrying location picker open"
        $xml = Open-BlinkitLocationPicker
    }
    if (-not ($xml -match "Select delivery location")) {
        Save-BlinkitHomeRecoveryArtifact "saved_home_picker_not_opened"
        if (Set-BlinkitHomeFromAddressBook) {
            return
        }
        throw "Saved Home picker did not open and address book fallback failed."
    }

    for ($attempt = 1; $attempt -le 2; $attempt++) {
        $homePoint = Wait-SavedHomeTapPoint 10
        if (-not $homePoint) {
            Save-BlinkitHomeRecoveryArtifact "saved_home_not_visible_attempt_$attempt"
            if ($attempt -lt 2) {
                $xml = Open-BlinkitLocationPicker
                continue
            }
            if (Set-BlinkitHomeFromAddressBook) {
                return
            }
            throw "Saved Home address was not visible in Blinkit location picker or address book."
        }

        $script:BlinkitHomeSelectionAttempted = $true
        Write-Phase "[home] tapping saved Home row at $($homePoint.X),$($homePoint.Y) (attempt $attempt)"
        adb shell input tap $homePoint.X $homePoint.Y
        Start-Sleep -Seconds 6
        $xml = Close-BlinkitReceiverPrompt

        $xml = Wait-BlinkitHomeMainScreen 25
        if (Test-BlinkitHomeSelected $xml) {
            $script:BlinkitHomeConfirmed = $true
            return
        }

        Save-BlinkitHomeRecoveryArtifact "saved_home_switch_failed_attempt_$attempt"
        if ($attempt -lt 2) {
            $xml = Open-BlinkitLocationPicker
        }
    }

    if (Set-BlinkitHomeFromAddressBook) {
        return
    }
    throw "Blinkit did not switch to saved Home address."
}

function Ensure-BlinkitHomeScreen {
    if ($script:BlinkitHomeConfirmed -and (Test-BlinkitTopNoDump)) {
        Write-Phase "[home] cached Home screen accepted"
        return
    }
    for ($i = 0; $i -lt 5; $i++) {
        if (-not (Test-BlinkitTopNoDump)) {
            Save-BlinkitHomeRecoveryArtifact "ensure_blinkit_home_back_loop_focus_lost"
            throw "Blinkit did not return to the Home search screen."
        }
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
        if (-not $xml -or $xml -notmatch "Unserviceable area|subtitle2|HOME -|Blinkit in|Search for atta|Select delivery location") {
            $xml = Wait-BlinkitLocationUiReady 5
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
            $script:BlinkitHomeConfirmed = $true
            return
        }
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 2
    }

    adb shell monkey -p $BlinkitPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 4
    $xml = Wait-BlinkitLocationUiReady 5
    if ((Test-BlinkitHomeSelected $xml) -and ($xml -match "Search for atta|Search for") -and ($xml -notmatch "Filters|Sort|Search across filters")) {
        $script:BlinkitHomeConfirmed = $true
        return
    }
    Save-BlinkitHomeRecoveryArtifact "ensure_blinkit_home_screen_retry_before_abort"
    try {
        Select-BlinkitHomeIfNeeded
    } catch {
        # keep the original abort with the root failure details, but keep artifact+retry context.
        throw $_
    }
    $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    if ((Test-BlinkitHomeSelected $xml) -and ($xml -match "Search for atta|Search for") -and ($xml -notmatch "Filters|Sort|Search across filters")) {
        $script:BlinkitHomeConfirmed = $true
        return
    }
    Save-BlinkitHomeRecoveryArtifact "ensure_blinkit_home_screen_failed"
    throw "Blinkit did not return to the Home search screen."
}

function Ensure-BlinkitHomePrecondition([string]$Stage) {
    Write-Phase "[home] enforcing saved Home precondition before $Stage"
    if ($script:BlinkitHomeConfirmed -and (Test-BlinkitTopNoDump)) {
        Write-Phase "[home] cached Home confirmation accepted before $Stage"
        return
    }
    $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    if ($xml -notmatch 'package="com.grofers.customerapp"' -or
            $xml -match "Unserviceable area|Santa Clara|Mountain View|Choose delivery location|Select delivery location|Your saved addresses|Search for area|Use current location|Add new address" -or
            -not (Test-BlinkitHomeSelected $xml)) {
        Select-BlinkitHomeIfNeeded
        Ensure-BlinkitHomeScreen
        return
    }

    if (-not (Test-BlinkitHomeSelected (Wait-BlinkitLocationUiReady 5))) {
        Select-BlinkitHomeIfNeeded
        Ensure-BlinkitHomeScreen
    }
}

function Ensure-BlinkitForegroundForInstruction {
    Write-Phase "[home] bringing Blinkit foreground for instruction capture"
    $anrDismissed = Dismiss-AnrFromWindowState
    if ($anrDismissed) {
        Start-BlinkitAndWait 90 | Out-Null
    } elseif ($script:BlinkitHomeConfirmed -and (Test-BlinkitTopNoDump)) {
        Write-Phase "[home] Blinkit already focused with saved Home confirmed"
        return
    }
    Start-BlinkitAndWait 90 | Out-Null
    Start-Sleep -Seconds 3
    if (-not (Test-BlinkitTopNoDump -RequireHomeActivity)) {
        Save-BlinkitHomeRecoveryArtifact "blinkit_not_foreground_before_instruction"
        throw "Blinkit was not foreground before submitting instruction."
    }
    Ensure-BlinkitHomePrecondition "instruction submission"
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

function Test-FlowFailureLog {
    $logs = adb logcat -d | Select-String "ORDER_RESULT|FLOW_FAILED|STATE: FAILED|items_failed="
    if (-not $logs) {
        return $false
    }

    $text = ($logs | Select-Object -Last 20) -join "`n"
    if ($text -match "FLOW_FAILED|STATE: FAILED") {
        return $true
    }
    if ($text -match "ORDER_RESULT" -and $text -match "items_failed=(\d+)") {
        return [int]$matches[1] -gt 0
    }
    return $false
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

function Get-OrderTargetTokens([string]$Text) {
    $normalized = $Text.ToLowerInvariant() -replace '[^a-z0-9 ]+', ' '
    $stopWords = @(
        "add", "buy", "cart", "get", "order", "please", "the", "to",
        "one", "a", "an", "some", "item", "items"
    )
    $tokens = @()
    foreach ($token in ($normalized -split '\s+')) {
        if (-not $token -or $stopWords -contains $token) {
            continue
        }
        if ($token.Length -gt 3 -and $token.EndsWith("s")) {
            $token = $token.Substring(0, $token.Length - 1)
        }
        if ($tokens -notcontains $token) {
            $tokens += $token
        }
    }
    return $tokens
}

function Test-XmlContainsTargetTokens([string]$Xml, [string[]]$Tokens) {
    if (-not $Xml -or -not $Tokens -or $Tokens.Count -eq 0) {
        return $false
    }
    $lower = [System.Net.WebUtility]::HtmlDecode($Xml).ToLowerInvariant()
    $matched = 0
    foreach ($token in $Tokens) {
        if ($lower -match [regex]::Escape($token)) {
            $matched++
        }
    }
    if ($Tokens.Count -eq 1) {
        return ($matched -eq 1)
    }
    return ($matched -ge [Math]::Min(2, $Tokens.Count))
}

function Try-LiveCartVerification([string]$InstructionText) {
    if (Dismiss-AnrFromWindowState) {
        Start-BlinkitAndWait 45 | Out-Null
    }
    $xml = Dismiss-SystemWaitDialog (Get-UiDump)
    if (-not $xml -or $xml -notmatch 'package="com.grofers.customerapp"') {
        return $false
    }

    $viewCart = Get-NodeCenterByTextOrDesc @("View cart", "View Cart", "Go to cart", "View items in cart") $xml
    if (-not $viewCart) {
        return $false
    }

    adb shell input keyevent 111 | Out-Null
    Start-Sleep -Milliseconds 300
    adb shell input tap $viewCart.X $viewCart.Y
    Start-Sleep -Seconds 3

    $cartXml = Dismiss-SystemWaitDialog (Get-UiDump)
    if ($cartXml -match "Place order|Pay using|Pay now|Payment|Proceed to payment|Continue to payment") {
        Write-Phase "live cart verifier reached checkout/payment boundary; force-stopping Blinkit"
        adb shell am force-stop $BlinkitPackage | Out-Null
        $script:BlinkitHomeConfirmed = $false
        Start-Sleep -Milliseconds 500
        return $false
    }

    $tokens = Get-OrderTargetTokens $InstructionText
    return (Test-XmlContainsTargetTokens $cartXml $tokens)
}

function Wait-ForFlowOutcome([int]$TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastLiveCartCheck = (Get-Date).AddSeconds(-30)
    $addClickedAt = $null
    do {
        if ($script:BlinkitAddToCartClicked) {
            if (($addClickedAt -ne $null) -and ((Get-Date) -gt $addClickedAt.AddSeconds(25))) {
                if (Dismiss-AnrFromWindowState) {
                    Start-BlinkitAndWait 45 | Out-Null
                    if (Test-FlowFailureLog) {
                        return "failed"
                    }
                } elseif (Test-FlowFailureLog) {
                    return "failed"
                }
            }
        } else {
            if (Dismiss-AnrFromWindowState -WaitOnly) {
                Start-Sleep -Seconds 2
                continue
            }
        }
        $logs = adb logcat -d | Select-String "BLINKIT_ADD_TO_CART_CLICKED|ADDING_TO_CART|AddingToCart|Adding item|BLINKIT_CART_INCREMENT_CONFIRMED|FLOW_SUCCESS|STATE: SUCCESS|cart increment confirmed|Item found in cart: true|FLOW_FAILED|STATE: FAILED|ORDER_RESULT|checkout_boundary|STATE: ORDER_DONE|MediaProjection state: null|Cannot trigger screenshot: Service not capturing"
        if ($logs) {
            $text = ($logs | Select-Object -Last 20) -join "`n"
            if ($text -match "MediaProjection state: null|Cannot trigger screenshot: Service not capturing") {
                return "capture_lost"
            }
            if ($text -match "BLINKIT_ADD_TO_CART_CLICKED|ADDING_TO_CART|AddingToCart|Adding item") {
                $script:BlinkitAddToCartClicked = $true
                if ($addClickedAt -eq $null) {
                    $addClickedAt = Get-Date
                    Write-Phase "Detected add-to-cart evidence; live cart verification delayed for 25s."
                }
            }
            if ($text -match "checkout_boundary") {
                return "failed"
            }
            if ($text -match "ORDER_RESULT") {
                if ($text -match "items_failed=(\d+)") {
                    if ([int]$matches[1] -gt 0) {
                        return "failed"
                    }
                    if (-not $script:BlinkitAddToCartClicked) {
                        Write-Phase "ORDER_RESULT observed before add-click evidence; treating as stale and continuing."
                        continue
                    }
                    return "success"
                }
                if (-not $script:BlinkitAddToCartClicked) {
                    Write-Phase "ORDER_RESULT observed before add-click evidence; treating as stale and continuing."
                    continue
                }
                return "success"
            }
            if ($text -match "BLINKIT_CART_INCREMENT_CONFIRMED|FLOW_SUCCESS|STATE: SUCCESS|cart increment confirmed|Item found in cart: true") {
                if (-not $script:BlinkitAddToCartClicked) {
                    Write-Phase "Cart-success signal observed before add-click evidence; treating as stale and continuing."
                    continue
                }
                return "success"
            }
            if ($text -match "FLOW_FAILED|STATE: FAILED") {
                return "failed"
            }
        }
        if ($script:BlinkitAddToCartClicked -and (Test-BackendCartVerified)) {
            if (Test-FlowFailureLog) {
                return "failed"
            }
            return "success"
        }
        if (-not $script:BlinkitAddToCartClicked -and (Test-BackendCartVerified)) {
            Write-Phase "Backend cart-verified signal observed before add-click evidence; treating as stale and continuing."
        }
        if ($script:BlinkitAddToCartClicked -and $addClickedAt -ne $null -and ((Get-Date) -gt $addClickedAt.AddSeconds(25)) -and ((Get-Date) -gt $lastLiveCartCheck.AddSeconds(8))) {
            $lastLiveCartCheck = Get-Date
            if (Test-FlowFailureLog) {
                return "failed"
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return "timeout"
}

function Test-UnsafeCheckoutAction($Response) {
    $rec = $Response.recommended_action
    $text = @(
        $rec.action_type,
        $rec.action_target,
        $rec.reasoning,
        $Response.failure_reason,
        $Response.workflow.state
    ) -join " "
    return ($text -match "(?i)\b(checkout|payment|pay|place\s+order|proceed\s+to\s+pay|order\s+now)\b")
}

function Get-AdbActionCoordinates($Action) {
    $coords = $Action.coordinates
    if (-not $coords) {
        $coords = $Action.fallback_coordinates
    }
    if (-not $coords -and $Action.bounding_box -and $Action.bounding_box.Count -eq 4) {
        return @{
            X = [int](([double]$Action.bounding_box[0] + [double]$Action.bounding_box[2]) / 2)
            Y = [int](([double]$Action.bounding_box[1] + [double]$Action.bounding_box[3]) / 2)
        }
    }
    if ($coords -and $coords.x -ne $null -and $coords.y -ne $null) {
        $x = [int][double]$coords.x
        $y = [int][double]$coords.y
        if ($x -gt 0 -and $y -gt 0) {
            return @{ X = $x; Y = $y }
        }
    }
    return $null
}

function Invoke-AdbBackendDecision([string]$InstructionText, [int]$Step, [object[]]$ActionHistory, [string]$LastActionResultJson) {
    $shot = Join-Path $LogsDir "$ScenarioName`_adb_step_$Step.png"
    $metaPath = Join-Path $LogsDir "$ScenarioName`_adb_step_$Step`_request.json"
    $responsePath = Join-Path $LogsDir "$ScenarioName`_adb_step_$Step`_response.json"
    adb exec-out screencap -p > $shot
    try {
        $tree = Get-UiDump
        $historyJson = ($ActionHistory | ConvertTo-Json -Depth 8 -Compress)
        if (-not $historyJson) {
            $historyJson = "[]"
        }
        $form = @{
            input_text = $InstructionText
            app_name = "Blinkit"
            detailed_tree_data = $tree
            sequence_step = "$Step"
            max_sequence_steps = "20"
            action_history = $historyJson
            action_history_json = $historyJson
            last_action_result_json = $LastActionResultJson
            api_version = "1.0"
            versioned_client_version = "adb-harness"
        }
        $form | ConvertTo-Json -Depth 8 | Set-Content -Path $metaPath
        $pythonCode = @"
import json
import sys
import requests
import urllib3

urllib3.disable_warnings()
shot, meta_path, out_path = sys.argv[1:4]
with open(meta_path, "r", encoding="utf-8") as f:
    data = json.load(f)
with open(shot, "rb") as image:
    response = requests.post(
        "https://127.0.0.1:8000/analyze-screenshot",
        data=data,
        files={"file": image},
        verify=False,
        timeout=180,
    )
response.raise_for_status()
with open(out_path, "w", encoding="utf-8") as f:
    f.write(response.text)
"@
        & python -c $pythonCode $shot $metaPath $responsePath
        if ($LASTEXITCODE -ne 0) {
            throw "ADB fallback backend request failed."
        }
        return (Get-Content -Raw -Path $responsePath | ConvertFrom-Json)
    } finally {
        Remove-Item -LiteralPath $shot -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $metaPath -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-AdbBackendAction($Response, [string]$InstructionText) {
    if (Test-UnsafeCheckoutAction $Response) {
        return "checkout_boundary"
    }
    if ($Response.task_completed -or $Response.is_completed -or $Response.recommended_action.is_completed) {
        return "success"
    }
    if (Test-BackendCartVerified) {
        return "success"
    }

    $action = $Response.recommended_action
    $actionType = ([string]$action.action_type).ToLowerInvariant()
    if ($actionType -eq "error") {
        return "failed"
    }

    $point = Get-AdbActionCoordinates $action
    if ($actionType -in @("click", "tap")) {
        if (-not $point) {
            return "failed"
        }
        adb shell input tap $point.X $point.Y
        Start-Sleep -Seconds 3
        $target = ([string]$action.action_target)
        if ($target -match "(?i)\b(add|cart|view cart)\b") {
            if (Try-LiveCartVerification $InstructionText) {
                return "success"
            }
        }
        return "continue"
    }

    if ($actionType -in @("type", "input", "enter_text")) {
        if ($point) {
            adb shell input tap $point.X $point.Y
            Start-Sleep -Milliseconds 500
        }
        $text = [string]($action.text_to_type ?? $Response.text_to_type)
        if (-not $text) {
            return "failed"
        }
        Set-TextByClipboard $text
        Start-Sleep -Milliseconds 500
        adb shell input keyevent 66
        Start-Sleep -Seconds 3
        return "continue"
    }

    if ($actionType -in @("scroll", "swipe")) {
        adb shell input swipe 540 1850 540 700 450
        Start-Sleep -Seconds 2
        return "continue"
    }

    if ($actionType -in @("back", "close")) {
        adb shell input keyevent 4
        Start-Sleep -Seconds 2
        return "continue"
    }

    if ($point) {
        adb shell input tap $point.X $point.Y
        Start-Sleep -Seconds 3
        return "continue"
    }
    return "failed"
}

function Invoke-AdbBackendFlow([string]$InstructionText, [int]$MaxSteps = 20) {
    $history = @()
    $lastResult = "{}"
    for ($step = 1; $step -le $MaxSteps; $step++) {
        Write-Phase "ADB fallback step $step"
        $response = Invoke-AdbBackendDecision $InstructionText $step $history $lastResult
        $action = $response.recommended_action
        $actionType = [string]$action.action_type
        $actionTarget = [string]$action.action_target
        $actionId = [string]$action.action_id
        $status = Invoke-AdbBackendAction $response $InstructionText
        $history += @{
            action_type = $actionType
            action_target = $actionTarget
            action_id = $actionId
            status = $status
        }
        $lastResult = (@{
            action_id = $actionId
            action_type = $actionType
            action_target = $actionTarget
            status = $(if ($status -eq "failed") { "failed" } else { "success" })
            notes = "adb-harness"
        } | ConvertTo-Json -Compress)
        if ($status -ne "continue") {
            return $status
        }
    }
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
        # Ensure beta overlay/capture is not active before touching Blinkit UI.
        adb shell am stopservice "$Package/.ScreenCaptureService" | Out-Null
        adb shell am force-stop $Package | Out-Null
        $script:BetaCaptureStarted = $false
        Start-Sleep -Seconds 1
        $resetDeadline = (Get-Date).AddSeconds(90)
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

        for ($i = 0; $i -lt 24; $i++) {
            if ((Get-Date) -ge $resetDeadline) {
                break
            }
            $xml = Get-UiDump
            if ((Get-Date) -ge $resetDeadline) {
                break
            }
            $control = Get-NodeCenterByTextOrDesc @("Decrease quantity", "Remove", "Delete", "−", "–", "-") $xml
            if (-not $control) {
                break
            }
            adb shell input tap $control.X $control.Y
            Start-Sleep -Seconds 1
        }

        adb shell input keyevent 3 | Out-Null
        adb shell am force-stop $BlinkitPackage | Out-Null
        $script:BlinkitHomeConfirmed = $false
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
$script:BlinkitAddToCartClicked = $false
$captureRetryUsed = $false

try {
    Reset-EmulatorAnrState

    Set-EmulatorNearHomeLocation
    Write-Phase "launching Blinkit and selecting saved Home address"
    adb shell am force-stop $BlinkitPackage | Out-Null
    Start-Sleep -Seconds 1
    Start-BlinkitAndWait 90 | Out-Null
    Select-BlinkitHomeIfNeeded
    Ensure-BlinkitHomeScreen
    Ensure-BlinkitHomePrecondition "initial capture"

    Start-BetaScreenCapture
    Start-BlinkitAndWait 90 | Out-Null
    Ensure-BlinkitHomePrecondition "final home verify"
    Ensure-BlinkitHomeScreen
    Write-Phase "resetting Blinkit cart before instruction"
    Reset-BlinkitCart
    Ensure-BlinkitForegroundForInstruction

    Write-Phase "submitting beta emulator instruction"
    if (-not (Test-BetaScreenCaptureServiceRunning)) {
        Start-BetaScreenCapture
        Ensure-BlinkitHomePrecondition "capture retry"
        Start-BlinkitAndWait 90 | Out-Null
        Ensure-BlinkitHomePrecondition "capture retry home verify"
        Ensure-BlinkitHomeScreen
    }
    Ensure-BlinkitForegroundForInstruction
    Enable-BetaAccessibility
    Wait-BetaAccessibilityConnected 15 | Out-Null
    $escapedInstruction = ConvertTo-AdbShellArg $Instruction
    adb shell "am broadcast -n $Package/.AutomationInstructionReceiver -a com.example.beta.SUBMIT_AUTOMATION_INSTRUCTION --es instruction $escapedInstruction" | Out-Null
    $instructionSubmitted = $true
    Start-Sleep -Seconds 1

    if (-not (Wait-ForLog "INSTRUCTION_RECEIVED|BLINKIT_SEARCH_STARTED" 20)) {
        throw "Beta did not receive the emulator instruction."
    }

    Write-Phase "waiting for safe add-to-cart verification"
    $outcome = Wait-ForFlowOutcome 240
    if ($outcome -eq "capture_lost" -and -not $captureRetryUsed) {
        $captureRetryUsed = $true
        Write-Phase "screen capture was lost; switching to ADB screenshot fallback"
        Ensure-BlinkitForegroundForInstruction
        Enable-BetaAccessibility
        Wait-BetaAccessibilityConnected 15 | Out-Null
        $outcome = Invoke-AdbBackendFlow $Instruction 20
    }
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
    if ($instructionSubmitted -and $script:BlinkitAddToCartClicked) {
        Write-Phase "resetting Blinkit cart after submitted instruction"
        Reset-BlinkitCart
    } elseif ($instructionSubmitted) {
        Write-Phase "skipping cart reset because add-to-cart was not clicked"
    } else {
        Write-Phase "skipping cart reset because no instruction was submitted"
    }
}

if ($result -ne 0) {
    throw "Blinkit flow test failed for '$Instruction'. See logs\$ScenarioName`_full_log.txt and logs\$ScenarioName`_crash_log.txt."
}

Write-Host "Blinkit flow test passed for '$Instruction' without checkout/payment."
