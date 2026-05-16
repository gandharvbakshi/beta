param(
    [int]$MaxRemoveTaps = 30,
    [int]$MaxBackTaps = 6,
    [int]$MaxFindScrolls = 3
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
$LogsDir = Join-Path $ProjectDir "logs"
$RunStamp = Get-Date -Format "yyyyMMdd_HHmmss"
$ArtifactPrefix = "manual_empty_cart_to_home_$RunStamp"
$InitialXmlPath = Join-Path $LogsDir "$ArtifactPrefix`_initial.xml"
$FinalXmlPath = Join-Path $LogsDir "$ArtifactPrefix`_final.xml"
$FinalScreenPath = Join-Path $LogsDir "$ArtifactPrefix`_final_screen.png"
$SummaryPath = Join-Path $LogsDir "$ArtifactPrefix`_summary.json"

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null

$events = New-Object System.Collections.Generic.List[object]
$removeTaps = 0
$confirmTaps = 0
$findScrolls = 0

function Write-Phase([string]$Message) {
    Write-Host "[empty-cart] $Message"
}

function Add-Event([string]$Type, [hashtable]$Data = @{}) {
    $eventData = [ordered]@{
        at = (Get-Date).ToString("o")
        type = $Type
    }
    foreach ($key in $Data.Keys) {
        $eventData[$key] = $Data[$key]
    }
    $events.Add([pscustomobject]$eventData) | Out-Null
}

function Require-Device {
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No connected emulator/device found. Prepare the emulator and rerun."
    }
}

function Get-UiDump {
    for ($i = 0; $i -lt 3; $i++) {
        adb shell "rm -f /sdcard/window.xml" | Out-Null
        $dumpResult = (adb shell timeout 8 uiautomator dump /sdcard/window.xml 2>&1) -join "`n"
        if ($dumpResult -notmatch "UI hierchary dumped to|UI hierarchy dumped to") {
            $dumpResult = (adb shell timeout 12 uiautomator dump --compressed /sdcard/window.xml 2>&1) -join "`n"
        }
        if ($dumpResult -match "UI hierchary dumped to|UI hierarchy dumped to") {
            $xml = (adb shell cat /sdcard/window.xml) -join "`n"
            if ($xml -match "<hierarchy") {
                return $xml
            }
        }
        Start-Sleep -Seconds 1
    }
    return ""
}

function Get-ForegroundPackage {
    $focus = (adb shell dumpsys window 2>$null | Select-String -Pattern "mCurrentFocus|mFocusedApp|topResumedActivity" | Select-Object -First 5) -join "`n"
    if ($focus -match "com\.grofers\.customerapp") {
        return "com.grofers.customerapp"
    }
    return ""
}

function Get-UiDumpAfterNavigation {
    for ($i = 0; $i -lt 3; $i++) {
        $dump = Get-UiDump
        if ($dump) {
            return $dump
        }
        Start-Sleep -Seconds 2
    }
    return ""
}

function Get-NodeAttribute([string]$Node, [string]$Name) {
    $match = [regex]::Match($Node, "\s$([regex]::Escape($Name))=""([^""]*)""")
    if (-not $match.Success) {
        return ""
    }
    return ([System.Net.WebUtility]::HtmlDecode($match.Groups[1].Value) -replace "\s+", " ").Trim()
}

function Get-ParsedNodes([string]$Xml) {
    $nodes = [regex]::Matches($Xml, "<node\b[^>]*>")
    foreach ($nodeMatch in $nodes) {
        $node = $nodeMatch.Value
        $bounds = Get-NodeAttribute $node "bounds"
        $boundsMatch = [regex]::Match($bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
        if (-not $boundsMatch.Success) {
            continue
        }

        $x1 = [int]$boundsMatch.Groups[1].Value
        $y1 = [int]$boundsMatch.Groups[2].Value
        $x2 = [int]$boundsMatch.Groups[3].Value
        $y2 = [int]$boundsMatch.Groups[4].Value

        [pscustomobject]@{
            Text = Get-NodeAttribute $node "text"
            Resource = Get-NodeAttribute $node "resource-id"
            Class = Get-NodeAttribute $node "class"
            Package = Get-NodeAttribute $node "package"
            Desc = Get-NodeAttribute $node "content-desc"
            Clickable = (Get-NodeAttribute $node "clickable") -eq "true"
            Enabled = (Get-NodeAttribute $node "enabled") -ne "false"
            Bounds = $bounds
            X = [int](($x1 + $x2) / 2)
            Y = [int](($y1 + $y2) / 2)
            X1 = $x1
            Y1 = $y1
            X2 = $x2
            Y2 = $y2
        }
    }
}

function Get-PaymentFooterTop([object[]]$Nodes) {
    $footerCandidates = @(
        $Nodes | Where-Object {
            $_.Resource -match "(?i)sticky_footer|cv_checkout_container|cl_cart_checkout|cl_cart_payment|tv_action_text" -or
            $_.Text -match "(?i)^Place Order$|^PAY USING$" -or
            $_.Desc -match "(?i)^Place Order$|^PAY USING$"
        }
    )
    if ($footerCandidates.Count -gt 0) {
        return (($footerCandidates | Sort-Object Y1 | Select-Object -First 1).Y1)
    }
    return 1960
}

function Test-IsForbiddenPaymentNode([object]$Node) {
    $label = "$($Node.Text) $($Node.Resource) $($Node.Desc)"
    return $label -match "(?i)place order|pay using|checkout_title|checkout_subtitle|cart_checkout|cart_payment|payment|cv_checkout"
}

function Find-RemovalControl([string]$Xml) {
    $nodes = @(Get-ParsedNodes $Xml)
    if ($nodes.Count -eq 0) {
        return $null
    }

    $footerTop = Get-PaymentFooterTop $nodes
    $maxTapY = [Math]::Min(1960, $footerTop - 10)
    $candidates = foreach ($node in $nodes) {
        if (-not $node.Enabled -or $node.Y -ge $maxTapY -or (Test-IsForbiddenPaymentNode $node)) {
            continue
        }

        $label = "$($node.Text) $($node.Resource) $($node.Desc)"
        $score = $null
        if ($node.Resource -match "(?i)icon_decrement$" -or $node.Desc -match "(?i)^Decrease quantity$") {
            $score = 0
        } elseif ($label -match "(?i)\bRemove\b") {
            $score = 1
        } elseif ($label -match "(?i)\bDelete\b") {
            $score = 2
        }

        if ($null -ne $score) {
            [pscustomobject]@{
                X = $node.X
                Y = $node.Y
                Score = $score
                Text = $node.Text
                Resource = $node.Resource
                Desc = $node.Desc
                Bounds = $node.Bounds
            }
        }
    }

    return @($candidates | Sort-Object Score, Y, X | Select-Object -First 1)[0]
}

function Find-ConfirmRemovalControl([string]$Xml) {
    if ($Xml -notmatch "(?i)remove item|remove .*cart|are you sure|delete item|clear cart") {
        return $null
    }

    $nodes = @(Get-ParsedNodes $Xml)
    $candidates = foreach ($node in $nodes) {
        if (-not $node.Enabled -or (Test-IsForbiddenPaymentNode $node)) {
            continue
        }

        $label = "$($node.Text) $($node.Desc)"
        if ($label -match "(?i)^\s*(Remove|Delete|Yes|Confirm)\s*$") {
            [pscustomobject]@{
                X = $node.X
                Y = $node.Y
                Text = $node.Text
                Resource = $node.Resource
                Desc = $node.Desc
                Bounds = $node.Bounds
            }
        }
    }

    return @($candidates | Sort-Object Y -Descending | Select-Object -First 1)[0]
}

function Test-CartEmpty([string]$Xml) {
    if ($Xml -match "(?i)cart is empty|your cart is empty|empty cart|start shopping|shop now") {
        return $true
    }
    if ($Xml -match "(?i)Place Order|PAY USING|Shipment of \d+ item|Checkout") {
        return $false
    }
    return -not (Find-RemovalControl $Xml)
}

function Find-GoBackControl([string]$Xml) {
    $nodes = @(Get-ParsedNodes $Xml)
    $candidate = $nodes | Where-Object {
        $_.Enabled -and $_.Y -lt 260 -and
        ($_.Desc -match "(?i)^Go back$" -or $_.Resource -match "(?i)ic_left$")
    } | Sort-Object Y, X | Select-Object -First 1

    return $candidate
}

function Test-BlinkitHome([string]$Xml) {
    if ($Xml -notmatch 'package="com\.grofers\.customerapp"') {
        return $false
    }
    if ($Xml -match "(?i)Checkout|Place Order|PAY USING|Shipment of \d+ item") {
        return $false
    }
    if ($Xml -match '(?i)text="Filters"|content-desc="Filters"|text="Sort"|content-desc="Sort"|People also bought|Showing results') {
        return $false
    }
    if ($Xml -match "(?i)Unserviceable|Select delivery location|Choose delivery location|Search for area|Your saved addresses") {
        return $false
    }
    return $Xml -match "(?i)Blinkit in|HOME\s*-|Search for atta"
}

function Save-FinalArtifacts([string]$Xml, [string]$Outcome) {
    if ($Xml) {
        Set-Content -Path $FinalXmlPath -Value $Xml -Encoding UTF8
    }
    adb exec-out screencap -p > $FinalScreenPath
    [pscustomobject]@{
        outcome = $Outcome
        remove_taps = $removeTaps
        confirm_taps = $confirmTaps
        find_scrolls = $findScrolls
        initial_xml = $InitialXmlPath
        final_xml = $FinalXmlPath
        final_screen = $FinalScreenPath
        events = $events
    } | ConvertTo-Json -Depth 6 | Set-Content -Path $SummaryPath -Encoding UTF8
    Write-Phase "summary: $SummaryPath"
    Write-Phase "final screenshot: $FinalScreenPath"
}

Require-Device

Write-Phase "manual-ready mode: expecting Blinkit to already be foregrounded on the cart or checkout screen"
$xml = Get-UiDump
if (-not $xml) {
    throw "Unable to read the current UI hierarchy."
}
Set-Content -Path $InitialXmlPath -Value $xml -Encoding UTF8
Add-Event "initial_dump" @{ xml = $InitialXmlPath }

if ($xml -notmatch 'package="com\.grofers\.customerapp"') {
    Save-FinalArtifacts $xml "not_blinkit"
    throw "Blinkit is not the foreground app."
}

while (-not (Test-CartEmpty $xml)) {
    if ($removeTaps -ge $MaxRemoveTaps) {
        Save-FinalArtifacts $xml "remove_limit_reached"
        throw "Cart still appears non-empty after $MaxRemoveTaps remove/decrement taps."
    }

    $confirm = Find-ConfirmRemovalControl $xml
    if ($confirm) {
        $confirmTaps++
        Write-Phase "confirming removal at $($confirm.X),$($confirm.Y) [$($confirm.Text)$($confirm.Desc)]"
        Add-Event "confirm_removal" @{ x = $confirm.X; y = $confirm.Y; text = $confirm.Text; desc = $confirm.Desc; bounds = $confirm.Bounds }
        adb shell input tap $confirm.X $confirm.Y
        Start-Sleep -Seconds 2
        $xml = Get-UiDump
        continue
    }

    $control = Find-RemovalControl $xml
    if ($control) {
        $removeTaps++
        Write-Phase "tapping cart removal control at $($control.X),$($control.Y) [$($control.Resource) $($control.Desc)]"
        Add-Event "tap_removal_control" @{ x = $control.X; y = $control.Y; resource = $control.Resource; desc = $control.Desc; bounds = $control.Bounds }
        adb shell input tap $control.X $control.Y
        Start-Sleep -Seconds 2
        $xml = Get-UiDump
        continue
    }

    if ($findScrolls -lt $MaxFindScrolls) {
        $findScrolls++
        Write-Phase "cart is still active, but no visible removal control found; scrolling toward the cart items"
        Add-Event "find_scroll" @{ number = $findScrolls }
        adb shell input swipe 540 780 540 1750 450
        Start-Sleep -Seconds 1
        $xml = Get-UiDump
        continue
    }

    Save-FinalArtifacts $xml "no_removal_control"
    throw "Cart still appears active, but no safe removal control was found."
}

Write-Phase "cart appears empty; navigating back to Blinkit home"
for ($i = 0; $i -lt $MaxBackTaps; $i++) {
    if (Test-BlinkitHome $xml) {
        Save-FinalArtifacts $xml "success"
        Write-Phase "success: cart empty and Blinkit home detected"
        exit 0
    }

    $backControl = Find-GoBackControl $xml
    if ($backControl) {
        Write-Phase "tapping Blinkit back control at $($backControl.X),$($backControl.Y)"
        Add-Event "tap_go_back" @{ x = $backControl.X; y = $backControl.Y; bounds = $backControl.Bounds }
        adb shell input tap $backControl.X $backControl.Y
    } else {
        Write-Phase "sending Android back"
        Add-Event "keyevent_back" @{ number = ($i + 1) }
        adb shell input keyevent 4
    }

    Start-Sleep -Seconds 2
    $xml = Get-UiDumpAfterNavigation
    if (-not $xml) {
        $foregroundPackage = Get-ForegroundPackage
        if ($foregroundPackage -eq "com.grofers.customerapp") {
            Add-Event "ui_dump_lost_after_empty" @{ foreground_package = $foregroundPackage }
            Save-FinalArtifacts "" "success_ui_dump_lost_after_empty"
            Write-Phase "success: cart empty; Blinkit remained foreground after home navigation"
            exit 0
        }
        Save-FinalArtifacts "" "ui_dump_lost"
        throw "Lost UI hierarchy while navigating back."
    }
}

Save-FinalArtifacts $xml "home_not_detected"
throw "Cart appears empty, but Blinkit home was not detected after $MaxBackTaps back attempts."
