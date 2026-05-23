param(
    [string]$Package = "com.zeptoconsumerapp",
    [string]$LaunchComponent = "com.zeptoconsumerapp/.MainActivity",
    [string]$SavedAddressName = "Home",
    [int]$SettleSeconds = 8,
    [int]$ReadyTimeoutSeconds = 30,
    [switch]$NoForceStop
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
$RunStamp = Get-Date -Format "yyyyMMdd_HHmmss"
$RunDir = Join-Path $ProjectDir "logs\commerce_onboarding\zepto_preflight_$RunStamp"
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
Set-Location $ProjectDir

function Save-TextFile([string]$Name, [string]$Text) {
    $path = Join-Path $RunDir $Name
    $Text | Set-Content -Path $path -Encoding UTF8
    return $path
}

function Get-UiDump([string]$Name) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        adb shell "rm -f /sdcard/window.xml" | Out-Null
        $dumpResult = (adb shell timeout 8 uiautomator dump /sdcard/window.xml 2>&1) -join "`n"
        Save-TextFile "$Name`_dump_attempt_$attempt.txt" $dumpResult | Out-Null
        if ($dumpResult -match "UI hierchary dumped to|UI hierarchy dumped to") {
            $xml = (adb shell cat /sdcard/window.xml 2>&1) -join "`n"
            if ($xml -match "<hierarchy") {
                Save-TextFile "$Name.xml" $xml | Out-Null
                return $xml
            }
        }
        Start-Sleep -Milliseconds 800
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

function Save-UiText([string]$Name, [string]$Xml) {
    $texts = @()
    $texts += Get-XmlAttributeValues $Xml "text"
    $texts += Get-XmlAttributeValues $Xml "content-desc"
    Save-TextFile "$Name`_text.txt" (($texts | Where-Object { $_ } | Select-Object -Unique) -join "`n") | Out-Null
}

function Test-ServiceableHome([string]$Xml) {
    if (-not $Xml) { return $false }
    $text = $Xml.ToLowerInvariant()
    return $text.Contains("com.zeptoconsumerapp:id/homepage-search-box") -and
        ($text.Contains("current address name is") -or $text.Contains("estimated delivery time is") -or $text.Contains("eta-address-details"))
}

function Test-AddressGate([string]$Xml) {
    if (-not $Xml) { return $false }
    $text = $Xml.ToLowerInvariant()
    return $text.Contains("select your address") -or
        $text.Contains("location permission is off") -or
        $text.Contains("request address from friend") -or
        $text.Contains("search your location")
}

function Wait-ForRecognizedSurface([string]$Prefix, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $TimeoutSeconds))
    $attempt = 1
    $latest = ""
    while ((Get-Date) -lt $deadline) {
        $latest = Get-UiDump "$Prefix`_$attempt"
        Save-UiText "$Prefix`_$attempt" $latest
        if ((Test-ServiceableHome $latest) -or (Test-AddressGate $latest)) {
            return $latest
        }
        Start-Sleep -Seconds 3
        $attempt++
    }
    return $latest
}

function Find-SavedAddressTap([string]$Xml, [string]$Name) {
    try {
        $doc = [xml]$Xml
        $needle = $Name.ToLowerInvariant()
        $nodes = $doc.SelectNodes("//node") | Where-Object {
            $desc = [System.Net.WebUtility]::HtmlDecode(($_.'content-desc' | Out-String).Trim()).ToLowerInvariant()
            $text = [System.Net.WebUtility]::HtmlDecode(($_.text | Out-String).Trim()).ToLowerInvariant()
            $_.clickable -eq "true" -and (
                $desc.Contains("address name is") -and $desc.Contains($needle) -or
                $text -eq $needle
            )
        }
        $node = $nodes | Select-Object -First 1
        if (-not $node) { return $null }
        if ($node.bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") { return $null }
        return [pscustomobject]@{
            X = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            Y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            Bounds = $node.bounds
        }
    } catch {
        Save-TextFile "find_saved_address_error.txt" $_.Exception.Message | Out-Null
        return $null
    }
}

$devices = adb devices | Select-String "`tdevice$"
if (-not $devices) {
    throw "No connected emulator/device found."
}

$installed = adb shell pm list packages $Package
if ($installed -notcontains "package:$Package") {
    throw "Required package is not installed on the device: $Package"
}

if (-not $NoForceStop) {
    adb shell am force-stop $Package | Out-Null
    Start-Sleep -Seconds 1
}

adb shell am start -n $LaunchComponent | Out-Null
Start-Sleep -Seconds $SettleSeconds

$before = Wait-ForRecognizedSurface "before" $ReadyTimeoutSeconds
$status = "unknown"
$tap = $null

if (Test-ServiceableHome $before) {
    $status = "ready"
} elseif (Test-AddressGate $before) {
    $tap = Find-SavedAddressTap $before $SavedAddressName
    if ($tap -eq $null) {
        $status = "address_gate_no_saved_address"
    } else {
        adb shell input tap $tap.X $tap.Y | Out-Null
        Start-Sleep -Seconds 6
        $after = Wait-ForRecognizedSurface "after" $ReadyTimeoutSeconds
        if (Test-ServiceableHome $after) {
            $status = "selected_saved_address_ready"
        } else {
            $status = "selected_saved_address_not_ready"
        }
    }
}

$summary = [ordered]@{
    package = $Package
    launch_component = $LaunchComponent
    saved_address_name = $SavedAddressName
    status = $status
    tapped_bounds = if ($tap) { $tap.Bounds } else { $null }
    run_dir = $RunDir
    captured_at = (Get-Date).ToString("s")
}
$summaryPath = Join-Path $RunDir "summary.json"
$summary | ConvertTo-Json -Depth 4 | Set-Content -Path $summaryPath -Encoding UTF8
if ($status -notin @("ready", "selected_saved_address_ready")) {
    adb exec-out screencap -p > (Join-Path $RunDir "screen.png")
}

Write-Host "[zepto/preflight] status=$status"
Write-Host "[zepto/preflight] summary: $summaryPath"
if ($status -notin @("ready", "selected_saved_address_ready")) {
    exit 1
}
