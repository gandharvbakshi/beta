param(
    [string]$SearchQuery = "home",
    [int]$MaxScrolls = 4,
    [int]$LaunchTimeoutSeconds = 45,
    [int]$HomeReadyTimeoutSeconds = 45,
    [switch]$NoForceStop
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectDir

$SwiggyPackage = "in.swiggy.android.instamart"
$SwiggyLaunchComponent = "$SwiggyPackage/in.swiggy.android.HomeIcon"
$SavedHomeAddressPatterns = @(
    '(?i)\b602\b',
    '(?i)\b2974\b',
    '(?i)\b3000\b',
    '(?i)17th\s+Cross',
    '(?i)Jains',
    '(?i)Jayanagar',
    '(?i)Siddanna',
    '(?i)\b560070\b',
    '(?i)Bengaluru',
    '(?i)Bangalore'
)
$SavedHomeLabelPatterns = @(
    '(?i)^Home$',
    '(?i)^Other$',
    '(?i)To\s+Other'
)
$SearchFieldExcludePatterns = @(
    '(?i)search_bar',
    '(?i)Search an area or address',
    '(?i)android\.widget\.EditText'
)
$SelectedHomeAddressPatterns = @(
    '(?i)\b602\b',
    '(?i)\b2974\b',
    '(?i)\b3000\b',
    '(?i)17th\s+Cross',
    '(?i)Jains',
    '(?i)Jayanagar',
    '(?i)Siddanna',
    '(?i)\b560070\b'
)

function Write-Phase([string]$Message) {
    Write-Host "[swiggy-address] $Message"
}

function Require-Device {
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No connected emulator/device found. Prepare the emulator and rerun."
    }
}

function Require-Package([string]$Package) {
    $installed = adb shell pm list packages $Package
    if ($installed -notcontains "package:$Package") {
        throw "Required package is not installed on the device: $Package"
    }
}

function ConvertTo-AdbInputText([string]$Value) {
    return (($Value.Trim() -replace '\s+', '%s') -replace '&', '\&')
}

function Get-UiDump {
    for ($i = 0; $i -lt 3; $i++) {
        adb shell "rm -f /data/local/tmp/window.xml" | Out-Null
        $dumpResult = (adb shell timeout 8 uiautomator dump /data/local/tmp/window.xml 2>&1) -join "`n"
        if ($dumpResult -notmatch "UI hierchary dumped to|UI hierarchy dumped to") {
            $dumpResult = (adb shell timeout 12 uiautomator dump --compressed /data/local/tmp/window.xml 2>&1) -join "`n"
        }
        if ($dumpResult -match "UI hierchary dumped to|UI hierarchy dumped to") {
            $xml = (adb shell cat /data/local/tmp/window.xml) -join "`n"
            if ($xml -match "<hierarchy") {
                return $xml
            }
        }
        Start-Sleep -Seconds 1
    }
    return ""
}

function Get-NodeCenterByPattern([string]$Xml, [string[]]$Patterns, [string[]]$ExcludePatterns = @()) {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }

    $nodes = [regex]::Matches($Xml, '<node\b[^>]*>')
    foreach ($node in $nodes) {
        $text = ([regex]::Match($node.Value, 'text="([^"]*)"')).Groups[1].Value
        $desc = ([regex]::Match($node.Value, 'content-desc="([^"]*)"')).Groups[1].Value
        $resource = ([regex]::Match($node.Value, 'resource-id="([^"]*)"')).Groups[1].Value
        $class = ([regex]::Match($node.Value, 'class="([^"]*)"')).Groups[1].Value
        $bounds = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $bounds.Success) {
            continue
        }

        $haystack = "$text`n$desc`n$resource`n$class"
        $isExcluded = $false
        foreach ($excludePattern in $ExcludePatterns) {
            if ($haystack -match $excludePattern) {
                $isExcluded = $true
                break
            }
        }
        if ($isExcluded) {
            continue
        }

        foreach ($pattern in $Patterns) {
            if (($text -match $pattern) -or ($desc -match $pattern) -or ($resource -match $pattern)) {
                return @{
                    X = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
                    Y = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
                    Text = $text
                    Description = $desc
                    Resource = $resource
                }
            }
        }
    }

    return $null
}

function Wait-ForUi([scriptblock]$Predicate, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Get-UiDump
        if (& $Predicate $xml) {
            return $xml
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $xml
}

function Test-SwiggyForeground([string]$Xml) {
    return $Xml -match 'package="in\.swiggy\.android\.instamart"'
}

function Test-AddressPicker([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "Select Your Location|Search an area or address|SAVED ADDRESSES|Select Delivery Address|Enter Location Manually|Unable to get location|VIEW ALL")
}

function Test-SelectedHome([string]$Xml) {
    $hasHomeLabel = $Xml -match '(?i)To Home|Selected address is Home|To Other|Selected address is Other'
    $strongMatches = 0
    foreach ($pattern in $SelectedHomeAddressPatterns) {
        if ($Xml -match $pattern) {
            $strongMatches++
        }
    }
    return ($hasHomeLabel -and $strongMatches -gt 0) -or ($strongMatches -ge 2)
}

function Test-StoreUnavailable([string]$Xml) {
    return $Xml -match "We will be right back|unusually high traffic"
}

function Test-CartSurface([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)\bCART\b|Add more items|Pay using|Pay\s*₹|Move to wishlist")
}

function Test-OrderingForSomeoneElsePrompt([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "ordering for someone else|someone else|No, it.?s for me")
}

function Test-HomeSearchSurface([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    if (Test-AddressPicker $Xml) {
        return $false
    }
    if (Test-StoreUnavailable $Xml) {
        return $false
    }
    $hasSearch = $Xml -match "Search for products|search_bar|Search for"
    $hasHomeTab = $Xml -match 'content-desc="Home"|text="Home"'
    return $hasSearch -and $hasHomeTab -and (Test-SelectedHome $Xml)
}

function Start-SwiggyAndWait {
    if (-not $NoForceStop) {
        Write-Phase "resetting Swiggy process before launch"
        adb shell am force-stop $SwiggyPackage | Out-Null
        Start-Sleep -Milliseconds 700
    }

    Write-Phase "launching Swiggy Instamart"
    adb shell am start -n $SwiggyLaunchComponent | Out-Null
    Start-Sleep -Seconds 5

    $xml = Wait-ForUi { param($candidate) Test-SwiggyForeground $candidate } $LaunchTimeoutSeconds
    if (Test-SwiggyForeground $xml) {
        return $xml
    }

    Write-Phase "direct launch did not foreground Swiggy; trying launcher intent"
    adb shell monkey -p $SwiggyPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 5

    $xml = Wait-ForUi { param($candidate) Test-SwiggyForeground $candidate } 20
    if (Test-SwiggyForeground $xml) {
        return $xml
    }

    throw "Swiggy Instamart did not become the foreground app."
}

function Open-AddressPicker([string]$Xml) {
    if (Test-AddressPicker $Xml) {
        return $Xml
    }

    Write-Phase "opening Swiggy address picker"
    $addressPoint = Get-NodeCenterByPattern $Xml @("address_selector_area", "Selected address", "location_header", "im_address_bar")
    if ($addressPoint) {
        adb shell input tap $addressPoint.X $addressPoint.Y | Out-Null
    } else {
        adb shell input tap 500 160 | Out-Null
    }

    $xml = Wait-ForUi { param($candidate) Test-AddressPicker $candidate } 15
    if (Test-AddressPicker $xml) {
        return $xml
    }

    throw "Could not open the Swiggy address picker."
}

function Select-SwiggyHomeTab([string]$Xml) {
    if (Test-HomeSearchSurface $Xml) {
        return $Xml
    }

    Write-Phase "switching Swiggy to the Home tab"
    $homeTabPoint = Get-NodeCenterByPattern $Xml @('^Home$')
    if ($homeTabPoint) {
        adb shell input tap $homeTabPoint.X $homeTabPoint.Y | Out-Null
    } else {
        adb shell input tap 108 2250 | Out-Null
    }

    Start-Sleep -Seconds 3
    return Get-UiDump
}

function Exit-SwiggyCartToHome([string]$Xml) {
    if (-not (Test-CartSurface $Xml)) {
        return $Xml
    }

    Write-Phase "leaving Swiggy cart for Home/search surface"
    $addMorePoint = Get-NodeCenterByPattern $Xml @("(?i)Add more items")
    if ($addMorePoint) {
        adb shell input tap $addMorePoint.X $addMorePoint.Y | Out-Null
    } else {
        adb shell input keyevent 4 | Out-Null
    }

    Start-Sleep -Seconds 2
    $nextXml = Wait-ForUi { param($candidate) (Test-HomeSearchSurface $candidate) -or -not (Test-CartSurface $candidate) } 12
    if (Test-HomeSearchSurface $nextXml) {
        return $nextXml
    }

    if (Test-CartSurface $nextXml) {
        Write-Phase "cart remained visible; trying Android back once"
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 2
        $nextXml = Wait-ForUi { param($candidate) (Test-HomeSearchSurface $candidate) -or -not (Test-CartSurface $candidate) } 12
    }

    return $nextXml
}

function Enter-AddressSearch([string]$Xml) {
    $searchPoint = Get-NodeCenterByPattern $Xml @("search_bar", "Search an area or address")
    if (-not $searchPoint) {
        throw "Swiggy address search bar was not found."
    }

    Write-Phase "searching saved addresses for Home"
    adb shell input tap $searchPoint.X $searchPoint.Y | Out-Null
    Start-Sleep -Milliseconds 300
    adb shell input keyevent 123 | Out-Null
    for ($i = 0; $i -lt 24; $i++) {
        adb shell input keyevent 67 | Out-Null
    }
    $query = ConvertTo-AdbInputText $SearchQuery
    adb shell input text $query | Out-Null
    Start-Sleep -Seconds 2
}

function Get-SavedHomePoint([string]$Xml) {
    $addressPoint = Get-NodeCenterByPattern $Xml $SavedHomeAddressPatterns $SearchFieldExcludePatterns
    if ($addressPoint) {
        return $addressPoint
    }
    return Get-NodeCenterByPattern $Xml $SavedHomeLabelPatterns $SearchFieldExcludePatterns
}

function Confirm-OrderingForSelfIfPrompt([string]$Xml = "") {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }
    if (-not (Test-OrderingForSomeoneElsePrompt $Xml)) {
        return $false
    }

    $selfPoint = Get-NodeCenterByPattern $Xml @(
        "(?i)No,?\s*it.?s\s+for\s+me",
        "(?i)^No\b.*for\s+me",
        "(?i)for\s+me$"
    )
    if (-not $selfPoint) {
        throw "Swiggy is asking whether the address is for someone else, but the 'No, it's for me' action was not found."
    }

    Write-Phase "confirming address is for me"
    adb shell input tap $selfPoint.X $selfPoint.Y | Out-Null
    Start-Sleep -Seconds 2
    return $true
}

function Select-SavedHomeAddress([string]$Xml) {
    try {
        Enter-AddressSearch $Xml
    } catch {
        Write-Phase "address search was not available; falling back to visible saved addresses"
    }

    for ($scroll = 0; $scroll -le $MaxScrolls; $scroll++) {
        $xml = Get-UiDump
        if (-not (Test-AddressPicker $xml)) {
            break
        }

        $homePoint = Get-SavedHomePoint $xml
        if ($homePoint) {
            Write-Phase "selecting saved Home address"
            adb shell input tap $homePoint.X $homePoint.Y | Out-Null
            Start-Sleep -Seconds 2
            Confirm-OrderingForSelfIfPrompt | Out-Null
            return
        }

        if ($scroll -lt $MaxScrolls) {
            Write-Phase "scrolling saved addresses for Home (attempt $($scroll + 1) of $MaxScrolls)"
            adb shell input swipe 520 1560 520 840 300 | Out-Null
            Start-Sleep -Seconds 1
        }
    }

    throw "Could not reveal or select the saved Home address in Swiggy."
}

function Wait-SwiggyHomeReady {
    Write-Phase "waiting for Swiggy Home/search surface"
    $deadline = (Get-Date).AddSeconds($HomeReadyTimeoutSeconds)
    do {
        $xml = Get-UiDump
        if (Confirm-OrderingForSelfIfPrompt $xml) {
            continue
        }
        if (Test-HomeSearchSurface $xml) {
            return $xml
        }
        if (Test-CartSurface $xml) {
            $xml = Exit-SwiggyCartToHome $xml
            if (Test-HomeSearchSurface $xml) {
                return $xml
            }
        }
        if (Test-StoreUnavailable $xml) {
            throw "Swiggy selected Home, but the app is showing a store-unavailable/high-traffic screen."
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    if (Test-StoreUnavailable $xml) {
        throw "Swiggy selected Home, but the app is showing a store-unavailable/high-traffic screen."
    }

    throw "Swiggy did not reach a Home/search surface with the saved Home address selected."
}

function Invoke-SwiggyHomePreflight {
    Require-Device
    Require-Package $SwiggyPackage

    $xml = Start-SwiggyAndWait
    if (Test-HomeSearchSurface $xml) {
        Write-Phase "success: Swiggy Instamart is already on saved Home and the Home/search surface."
        return
    }

    if (Test-CartSurface $xml) {
        $xml = Exit-SwiggyCartToHome $xml
        if (Test-HomeSearchSurface $xml) {
            Write-Phase "success: Swiggy Instamart left cart and reached the Home/search surface."
            return
        }
    }

    if (-not (Test-AddressPicker $xml)) {
        $xml = Select-SwiggyHomeTab $xml
        if (Test-HomeSearchSurface $xml) {
            Write-Phase "success: Swiggy Instamart is already on saved Home and the Home/search surface."
            return
        }
    }

    $xml = Open-AddressPicker $xml
    Select-SavedHomeAddress $xml
    Wait-SwiggyHomeReady | Out-Null
    Write-Phase "success: Swiggy Instamart is on saved Home and the Home/search surface."
}

Invoke-SwiggyHomePreflight
