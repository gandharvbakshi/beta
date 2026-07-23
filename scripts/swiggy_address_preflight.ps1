param(
    [string]$SearchQuery = "home",
    [int]$MaxScrolls = 4,
    [int]$LaunchTimeoutSeconds = 45,
    [int]$HomeReadyTimeoutSeconds = 45,
    [string]$SwiggyPackage = "in.swiggy.android",
    [switch]$NoForceStop
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectDir

$SwiggyLaunchComponents = @(
    "$SwiggyPackage/in.swiggy.android.activities.HomeActivity",
    "$SwiggyPackage/in.swiggy.android.HomeIcon",
    "$SwiggyPackage/in.swiggy.android.FestiveIcon",
    "$SwiggyPackage/in.swiggy.android.HomeIconClone"
)
$SwiggyResourcePackages = @(
    $SwiggyPackage,
    "in.swiggy.android",
    "in.swiggy.android.instamart"
) | Select-Object -Unique
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
$OutOfServiceSelectedLocationPatterns = @(
    '(?i)\bUkiah\b',
    '(?i)\bCA\s*95482\b',
    '(?i)Vista\s+Del\s+Lago',
    '(?i)Mountain\s+View',
    '(?i)\bCA\s*94043\b',
    '(?i)\bUSA\b'
)
$script:SavedHomeSelectionAttempted = $false

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
        $dumpResult = (adb shell timeout 20 uiautomator dump --compressed /data/local/tmp/window.xml 2>&1) -join "`n"
        if ($dumpResult -notmatch "UI hierchary dumped to|UI hierarchy dumped to") {
            $dumpResult = (adb shell timeout 20 uiautomator dump /data/local/tmp/window.xml 2>&1) -join "`n"
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
        $left = [int]$bounds.Groups[1].Value
        $top = [int]$bounds.Groups[2].Value
        $right = [int]$bounds.Groups[3].Value
        $bottom = [int]$bounds.Groups[4].Value
        if ($right -le $left -or $bottom -le $top) {
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
                    X = [int](($left + $right) / 2)
                    Y = [int](($top + $bottom) / 2)
                    Text = $text
                    Description = $desc
                    Resource = $resource
                }
            }
        }
    }

    return $null
}

function Get-NodeCenterByResourceId([string]$Xml, [string[]]$ResourceIds) {
    if (-not $Xml) {
        $Xml = Get-UiDump
    }

    $nodes = [regex]::Matches($Xml, '<node\b[^>]*>')
    foreach ($resourceId in $ResourceIds) {
        foreach ($node in $nodes) {
            $resource = ([regex]::Match($node.Value, 'resource-id="([^"]*)"')).Groups[1].Value
            if ($resource -ne $resourceId) {
                continue
            }
            $bounds = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $bounds.Success) {
                continue
            }
            $left = [int]$bounds.Groups[1].Value
            $top = [int]$bounds.Groups[2].Value
            $right = [int]$bounds.Groups[3].Value
            $bottom = [int]$bounds.Groups[4].Value
            if ($right -le $left -or $bottom -le $top) {
                continue
            }

            return @{
                X = [int](($left + $right) / 2)
                Y = [int](($top + $bottom) / 2)
                Resource = $resource
            }
        }
    }

    return $null
}

function Tap-SwiggyAddressSelector([string]$Xml) {
    $addressResourceIds = foreach ($resourcePackage in $SwiggyResourcePackages) {
        "${resourcePackage}:id/address_selector_area"
        "${resourcePackage}:id/im_address_bar"
        "${resourcePackage}:id/location_header"
    }
    $addressPoint = Get-NodeCenterByResourceId $Xml $addressResourceIds
    if (-not $addressPoint) {
        $addressPoint = Get-NodeCenterByPattern $Xml @("address_selector_area", "Selected address", "location_header", "im_address_bar", "(?i)\bUkiah\b", "(?i)\bCA\s*95482\b", "(?i)Vista\s+Del\s+Lago", "(?i)Mountain\s+View", "(?i)\bCA\s*94043\b")
    }

    if ($addressPoint) {
        adb shell input tap $addressPoint.X $addressPoint.Y | Out-Null
    } else {
        adb shell input tap 500 160 | Out-Null
    }
}

function Wait-ForUi([scriptblock]$Predicate, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Get-UiDump
        if (Resolve-AppUnresponsiveDialog $xml) {
            Start-Sleep -Seconds 1
            continue
        }
        if (& $Predicate $xml) {
            return $xml
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $xml
}

function Get-AppUnresponsiveDialogTitle([string]$Xml) {
    if (-not $Xml) {
        return ""
    }
    if ($Xml -notmatch 'android:id/aerr_(close|wait)') {
        return ""
    }

    $match = [regex]::Match($Xml, 'text="([^"]*(?i:responding)[^"]*)"')
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return "Application Not Responding"
}

function Resolve-AppUnresponsiveDialog([string]$Xml) {
    $title = Get-AppUnresponsiveDialogTitle $Xml
    if (-not $title) {
        return $false
    }

    if ($title -match '(?i)instamart|swiggy|in\.swiggy\.android(?:\.instamart)?') {
        throw "Swiggy Instamart is not responding; cannot continue preflight until the app recovers or the emulator is restarted."
    }

    Write-Phase "closing non-Swiggy ANR dialog: $title"
    $closePoint = Get-NodeCenterByPattern $Xml @("android:id/aerr_close", "(?i)^Close app$")
    if ($closePoint) {
        adb shell input tap $closePoint.X $closePoint.Y | Out-Null
    } else {
        adb shell input tap 540 1205 | Out-Null
    }
    return $true
}

function Test-SwiggyForeground([string]$Xml) {
    return $Xml -match 'package="in\.swiggy\.android(?:\.instamart)?"'
}

function Test-SwiggySplashSurface([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    $nodeCount = [regex]::Matches($Xml, '<node\b').Count
    $hasUsableSurface = $Xml -match "(?i)Search for|Select Your Location|Choose a delivery address|Search an area or address|SAVED ADDRESSES|fragment_view_pager|discovery_fragment|food_listing|bottom_bar_parent|address_selector_area|address_selector_view|location_header|im_address_bar|content-desc=`"Home`"|text=`"Home`"|Your cart is getting lonely|Add more items|et_search_query_v2"
    $hasOnlyLaunchRoot = ($nodeCount -le 3) -and ($Xml -match 'resource-id="in\.swiggy\.android\.instamart:id/content"')
    return $hasOnlyLaunchRoot -or ((-not $hasUsableSurface) -and $nodeCount -le 6)
}

function Test-SwiggyLaunchReady([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and -not (Test-SwiggySplashSurface $Xml)
}

function Test-AddressPicker([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "Select Your Location|Choose a delivery address|Search an area or address|SAVED ADDRESSES|Select Delivery Address|Add New Address|Enter Location Manually|Unable to get location|VIEW ALL")
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
    return $Xml -match "We will be right back|unusually high traffic|currently unserviceable|store is currently unserviceable|store or delivery is not available|come back later to place the order"
}

function Test-ClearCartUnserviceableModal([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)existing cart is unserviceable|clear your cart to continue|different location|Clear Cart")
}

function Clear-UnserviceableCartModal([string]$Xml) {
    if (-not (Test-ClearCartUnserviceableModal $Xml)) {
        return $Xml
    }

    Write-Phase "clearing Swiggy unserviceable existing cart modal"
    $clearPoint = Get-NodeCenterByPattern $Xml @("(?i)^Clear Cart$", "(?i)clear your cart to continue")
    if ($clearPoint) {
        adb shell input tap $clearPoint.X $clearPoint.Y | Out-Null
    } else {
        adb shell input tap 540 1355 | Out-Null
    }
    Start-Sleep -Seconds 3
    return Get-UiDump
}

function Test-SelectedOutOfServiceLocation([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    foreach ($pattern in $OutOfServiceSelectedLocationPatterns) {
        if ($Xml -match $pattern) {
            return $true
        }
    }
    return $false
}

function Test-CartSurface([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }

    $hasNativeHomeSurface = $Xml -match "(?i)fragment_view_pager|discovery_fragment|food_listing|bottom_bar_parent"
    $hasFullCartMarkers = $Xml -match "(?i)cart_review_items|Cart Header|Regular cart|Maxxsaver cart|Add more items|Pay using|Pay\s*₹|Pay₹|Payment Options|Preferred Payment|More Payment Options|Credit &amp; Debit Cards|Move to wishlist|open the home page|might have missed"
    $hasEmptyCartMarkers = $Xml -match "(?i)Your cart is getting lonely|Fill it up with all things good|Start Shopping"
    $hasProductSearchMarkers = $Xml -match "(?i)et_search_query_v2|open_item_v4|search_results|controller_facets_bar_discovery|\bSort By\b|\bPrice\b"
    $hasCartOnlyMarker = ($Xml -match "(?i)\bCART\b") -and -not $hasNativeHomeSurface -and -not $hasProductSearchMarkers -and -not ($Xml -match "(?i)Search for|search_bar|content-desc=`"Home`"|text=`"Home`"")
    return $hasFullCartMarkers -or $hasEmptyCartMarkers -or $hasCartOnlyMarker
}

function Test-EmptyCartSurface([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)Your cart is getting lonely|Fill it up with all things good|Start Shopping")
}

function Test-CheckoutPaymentSurface([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)Payment Options|Preferred Payment|More Payment Options|Credit &amp; Debit Cards|Pay₹|Pay\s+to\s+Instamart|OTP Verification|Submit\s*&amp;\s*Pay|Submit\s*&\s*Pay|Complete this payment|Go to bank page|Payment Failed|Cancel Transaction|Try other Payment Methods")
}

function Test-CancelTransactionPrompt([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)Cancel Transaction\?|ongoing transaction|btn_yes")
}

function Confirm-CancelTransactionPrompt([string]$Xml) {
    if (-not (Test-CancelTransactionPrompt $Xml)) {
        return $Xml
    }

    Write-Phase "confirming Swiggy payment transaction cancel"
    $yesPoint = Get-NodeCenterByPattern $Xml @("(?i)^YES$", "btn_yes")
    if ($yesPoint) {
        adb shell input tap $yesPoint.X $yesPoint.Y | Out-Null
    } else {
        adb shell input tap 890 1310 | Out-Null
    }
    Start-Sleep -Seconds 3
    return Get-UiDump
}

function Test-PaymentFailedSurface([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)Payment Failed|Error while debiting|Try other Payment Methods|payment-alert-header-title")
}

function Dismiss-PaymentFailedSurface([string]$Xml) {
    if (-not (Test-PaymentFailedSurface $Xml)) {
        return $Xml
    }

    Write-Phase "dismissing Swiggy payment-failed sheet"
    $closePoint = Get-NodeCenterByPattern $Xml @("(?i)click here to close", "payment-alert-header-title")
    if ($closePoint -and $closePoint.X -gt 900) {
        adb shell input tap $closePoint.X $closePoint.Y | Out-Null
    } else {
        adb shell input tap 995 1727 | Out-Null
    }
    Start-Sleep -Seconds 2
    return Get-UiDump
}

function Test-ProductSearchSurface([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    if (Test-HomeShellSurface $Xml -or Test-AddressPicker $Xml -or Test-CartSurface $Xml) {
        return $false
    }

    return (Test-ProductListingSurface $Xml) -or ($Xml -match "(?i)et_search_query_v2|A few ideas to get you started|YOUR PAST SEARCHES|Search for '")
}

function Test-ProductListingSurface([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    if (Test-AddressPicker $Xml -or Test-CartSurface $Xml) {
        return $false
    }

    $hasListingMarkers = $Xml -match "(?i)listing_container_root|disc_container_listing|open_item_v4|\bSort By\b|\bPrice Drop\b|\d+\s+items\s+in"
    $hasHomeAddressMarkers = $Xml -match "(?i)address_selector_area|address_selector_view|location_header|im_address_bar|Selected address"
    return $hasListingMarkers -and -not $hasHomeAddressMarkers
}

function Test-ProductDetailSurface([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    if (Test-AddressPicker $Xml -or Test-CartSurface $Xml -or Test-HomeShellSurface $Xml -or Test-ProductSearchSurface $Xml) {
        return $false
    }

    $hasNativePdpMarker = $Xml -match "(?i)im_pdp_container|pdp_price_header_v2|quantityTextCrouton|wishlist_toolbar_icon|Hide product details"
    $hasPdpTextMarker = ($Xml -match "(?i)Product Details|Product Information|Similar Products") -and ($Xml -match "(?i)add_to_cart|ADD")
    return $hasNativePdpMarker -or $hasPdpTextMarker
}

function Test-OrderingForSomeoneElsePrompt([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    return [bool](Get-NodeCenterByPattern $Xml @("(?i)ordering\s+for\s+someone\s+else", "(?i)No,?\s*it.?s\s+for\s+me"))
}

function Test-HomeShellSurface([string]$Xml) {
    if (-not (Test-SwiggyForeground $Xml)) {
        return $false
    }
    if (Test-AddressPicker $Xml) {
        return $false
    }
    if (Test-CartSurface $Xml) {
        return $false
    }
    if (Test-ProductListingSurface $Xml) {
        return $false
    }

    $hasSearch = $Xml -match "Search for products|search_bar|Search for"
    $hasNativeHomeSurface = $Xml -match "(?i)fragment_view_pager|discovery_fragment|food_listing|bottom_bar_parent"
    $hasAddressSelector = $Xml -match "(?i)address_selector_area|address_selector_view|location_header|im_address_bar"
    $hasHomeTab = $Xml -match 'content-desc="Home"|text="Home"'
    return ($hasSearch -or $hasNativeHomeSurface -or $hasAddressSelector) -and ($hasHomeTab -or $hasNativeHomeSurface -or $hasAddressSelector)
}

function Test-HomeSearchSurface([string]$Xml, [switch]$TrustRecentSavedHomeSelection) {
    if (-not (Test-HomeShellSurface $Xml)) {
        return $false
    }
    if (Test-StoreUnavailable $Xml) {
        return $false
    }
    if (Test-SelectedHome $Xml) {
        return $true
    }
    if (-not (Test-SelectedOutOfServiceLocation $Xml) -and ($Xml -match "(?i)fragment_view_pager|discovery_fragment|food_listing|bottom_bar_parent")) {
        return $true
    }
    return [bool]($TrustRecentSavedHomeSelection -and -not (Test-AddressPicker $Xml) -and -not (Test-StoreUnavailable $Xml))
}

function Test-BlockingStoreUnavailable([string]$Xml) {
    return (Test-StoreUnavailable $Xml) -and -not (Test-HomeShellSurface $Xml) -and -not (Test-AddressPicker $Xml)
}

function Test-CouponlessSuccessModal([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)couponless_success_card_yay_button|couponless_success_card_close_button|FREE DELIVERY Unlocked|Offer auto-applied|Woohoo! You got free delivery|YAY!")
}

function Test-SwiggyMoneyWalletModal([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and
        ($Xml -match "in\.swiggy\.android(?:\.instamart)?:id/close") -and
        ($Xml -match "in\.swiggy\.android(?:\.instamart)?:id/widget_lottie|content-desc=`"Cancel`"")
}

function Test-PreviousOrderRatingPrompt([string]$Xml) {
    return (Test-SwiggyForeground $Xml) -and ($Xml -match "(?i)Rate your previous order")
}

function Dismiss-CouponlessSuccessModal([string]$Xml) {
    if (-not (Test-CouponlessSuccessModal $Xml)) {
        return $Xml
    }

    Write-Phase "dismissing Swiggy couponless success modal"
    $dismissPoint = Get-NodeCenterByPattern $Xml @(
        "(?i)couponless_success_card_yay_button",
        "(?i)^YAY!$",
        "(?i)couponless_success_card_close_button"
    )
    if ($dismissPoint) {
        adb shell input tap $dismissPoint.X $dismissPoint.Y | Out-Null
    } else {
        adb shell input keyevent 4 | Out-Null
    }
    Start-Sleep -Seconds 2
    return Get-UiDump
}

function Dismiss-SwiggyMoneyWalletModal([string]$Xml) {
    if (-not (Test-SwiggyMoneyWalletModal $Xml)) {
        return $Xml
    }

    Write-Phase "dismissing Swiggy money wallet modal"
    $dismissPoint = Get-NodeCenterByPattern $Xml @(
        "in\.swiggy\.android\.instamart:id/close",
        "(?i)^Cancel$"
    )
    if ($dismissPoint) {
        adb shell input tap $dismissPoint.X $dismissPoint.Y | Out-Null
    } else {
        adb shell input keyevent 4 | Out-Null
    }
    Start-Sleep -Seconds 2
    return Get-UiDump
}

function Dismiss-PreviousOrderRatingPrompt([string]$Xml) {
    if (-not (Test-PreviousOrderRatingPrompt $Xml)) {
        return $Xml
    }

    Write-Phase "dismissing Swiggy previous-order rating prompt"
    $closePoint = Get-NodeCenterByPattern $Xml @("(?i)^Close$", "(?i)^Dismiss$")
    if ($closePoint) {
        adb shell input tap $closePoint.X $closePoint.Y | Out-Null
    } else {
        adb shell input tap 1020 1885 | Out-Null
    }
    Start-Sleep -Seconds 2
    return Get-UiDump
}

function Exit-StoreUnavailableToHome([string]$Xml) {
    if (-not (Test-BlockingStoreUnavailable $Xml)) {
        return $Xml
    }

    Write-Phase "leaving Swiggy store-unavailable screen"
    adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 2

    return Wait-ForUi {
        param($candidate)
        (Test-HomeShellSurface $candidate) -or
            (Test-AddressPicker $candidate)
    } 12
}

function Start-SwiggyAndWait {
    if (-not $NoForceStop) {
        Write-Phase "resetting Swiggy process before launch"
        adb shell am force-stop $SwiggyPackage | Out-Null
        Start-Sleep -Milliseconds 700
    }

    foreach ($component in $SwiggyLaunchComponents) {
        Write-Phase "launching Swiggy Instamart via $component"
        adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component | Out-Null
        Start-Sleep -Seconds 5

        $xml = Wait-ForUi { param($candidate) Test-SwiggyLaunchReady $candidate } $LaunchTimeoutSeconds
        if (Test-SwiggyLaunchReady $xml) {
            return $xml
        }
    }

    Write-Phase "component launch did not foreground Swiggy; trying launcher intent"
    adb shell monkey -p $SwiggyPackage -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 5

    $xml = Wait-ForUi { param($candidate) Test-SwiggyLaunchReady $candidate } $LaunchTimeoutSeconds
    if (Test-SwiggyLaunchReady $xml) {
        return $xml
    }

    throw "Swiggy Instamart did not become the foreground app."
}

function Open-AddressPicker([string]$Xml) {
    if (Test-SwiggySplashSurface $Xml) {
        Write-Phase "waiting for Swiggy launch splash to finish"
        $Xml = Wait-ForUi { param($candidate) Test-SwiggyLaunchReady $candidate } $LaunchTimeoutSeconds
    }
    if ((Test-StoreUnavailable $Xml) -and -not (Test-SelectedOutOfServiceLocation $Xml)) {
        throw "Swiggy selected Home, but the app is showing a store-unavailable/high-traffic screen."
    }
    if (Test-AddressPicker $Xml) {
        return $Xml
    }
    if (Test-ProductDetailSurface $Xml) {
        $Xml = Exit-SwiggyProductDetailToHome $Xml
        if (Test-AddressPicker $Xml) {
            return $Xml
        }
    }
    if (Test-CartSurface $Xml) {
        $Xml = Exit-SwiggyCartToHome $Xml
        if (Test-AddressPicker $Xml) {
            return $Xml
        }
    }
    if (Test-ProductSearchSurface $Xml) {
        $Xml = Exit-SwiggyProductSearchToHome $Xml
        if (Test-AddressPicker $Xml) {
            return $Xml
        }
    }

    Write-Phase "opening Swiggy address picker"
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        Tap-SwiggyAddressSelector $Xml
        $xml = Wait-ForUi { param($candidate) Test-AddressPicker $candidate } 8
        if (Test-AddressPicker $xml) {
            return $xml
        }
        if ((Test-StoreUnavailable $xml) -and -not (Test-SelectedOutOfServiceLocation $xml)) {
            throw "Swiggy selected Home, but the app is showing a store-unavailable/high-traffic screen."
        }
        if (-not (Test-SelectedOutOfServiceLocation $xml)) {
            break
        }
        Write-Phase "retrying Swiggy address selector from out-of-service surface"
    }

    throw "Could not open the Swiggy address picker from the selected out-of-service/high-traffic surface."
}

function Select-SwiggyHomeTab([string]$Xml) {
    if (Test-HomeSearchSurface $Xml) {
        return $Xml
    }
    if (Test-HomeShellSurface $Xml) {
        return $Xml
    }
    if (Test-ProductDetailSurface $Xml) {
        $Xml = Exit-SwiggyProductDetailToHome $Xml
        if (Test-HomeSearchSurface $Xml -or Test-HomeShellSurface $Xml) {
            return $Xml
        }
    }
    if (Test-CartSurface $Xml) {
        return Exit-SwiggyCartToHome $Xml
    }
    if (Test-ProductSearchSurface $Xml) {
        return Exit-SwiggyProductSearchToHome $Xml
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
    if (-not (Test-CartSurface $Xml) -and -not (Test-CheckoutPaymentSurface $Xml)) {
        return $Xml
    }

    Write-Phase "leaving Swiggy cart for Home/search surface"
    if (Test-CheckoutPaymentSurface $Xml) {
        Write-Phase "leaving Swiggy payment/checkout screen"
        for ($attempt = 0; $attempt -lt 4; $attempt++) {
            if (Test-CancelTransactionPrompt $Xml) {
                $Xml = Confirm-CancelTransactionPrompt $Xml
            }
            if (Test-PaymentFailedSurface $Xml) {
                $Xml = Dismiss-PaymentFailedSurface $Xml
            }
            if (-not (Test-CheckoutPaymentSurface $Xml)) {
                break
            }
            adb shell input keyevent 4 | Out-Null
            Start-Sleep -Seconds 2
            $Xml = Get-UiDump
        }
        if (Test-HomeSearchSurface $Xml -or Test-ProductSearchSurface $Xml -or (Test-CartSurface $Xml -and -not (Test-CheckoutPaymentSurface $Xml))) {
            return $Xml
        }
    }

    $emptyCartPoint = Get-NodeCenterByPattern $Xml @("(?i)^Start Shopping$", "(?i)Your cart is getting lonely")
    if ($emptyCartPoint) {
        adb shell input tap $emptyCartPoint.X $emptyCartPoint.Y | Out-Null
    } else {
        $addMorePoint = Get-NodeCenterByPattern $Xml @("(?i)Add more items")
        if ($addMorePoint) {
            adb shell input tap $addMorePoint.X $addMorePoint.Y | Out-Null
        } else {
            $homeNudgePoint = Get-NodeCenterByPattern $Xml @("(?i)open the home page", "(?i)might have missed")
            if ($homeNudgePoint) {
                adb shell input tap $homeNudgePoint.X $homeNudgePoint.Y | Out-Null
            } else {
                adb shell input keyevent 4 | Out-Null
            }
        }
    }

    Start-Sleep -Seconds 2
    $nextXml = Wait-ForUi { param($candidate) (Test-HomeSearchSurface $candidate) -or (Test-ProductDetailSurface $candidate) -or -not (Test-CartSurface $candidate) } 12
    if (Test-HomeSearchSurface $nextXml) {
        return $nextXml
    }
    if (Test-ProductDetailSurface $nextXml) {
        return Exit-SwiggyProductDetailToHome $nextXml
    }
    if (Test-ProductSearchSurface $nextXml) {
        return Exit-SwiggyProductSearchToHome $nextXml
    }

    if (Test-CartSurface $nextXml) {
        Write-Phase "cart remained visible; trying Android back once"
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 2
        $nextXml = Wait-ForUi { param($candidate) (Test-HomeSearchSurface $candidate) -or (Test-ProductDetailSurface $candidate) -or -not (Test-CartSurface $candidate) } 12
    }

    if (Test-ProductDetailSurface $nextXml) {
        return Exit-SwiggyProductDetailToHome $nextXml
    }
    if (Test-ProductSearchSurface $nextXml) {
        return Exit-SwiggyProductSearchToHome $nextXml
    }

    return $nextXml
}

function Exit-SwiggyProductSearchToHome([string]$Xml) {
    if (-not (Test-ProductSearchSurface $Xml)) {
        return $Xml
    }

    Write-Phase "leaving Swiggy product-search surface for Home/search surface"
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        $backResourceIds = foreach ($resourcePackage in $SwiggyResourcePackages) {
            "${resourcePackage}:id/back_button"
        }
        $backPoint = Get-NodeCenterByResourceId $Xml $backResourceIds
        if ($backPoint) {
            adb shell input tap $backPoint.X $backPoint.Y | Out-Null
        } else {
            adb shell input keyevent 4 | Out-Null
        }
        Start-Sleep -Seconds 2
        $nextXml = Get-UiDump
        if (Test-HomeSearchSurface $nextXml -or Test-HomeShellSurface $nextXml) {
            return $nextXml
        }
        if (-not (Test-ProductSearchSurface $nextXml)) {
            return $nextXml
        }
        $Xml = $nextXml
    }

    return Get-UiDump
}

function Exit-SwiggyProductDetailToHome([string]$Xml) {
    if (-not (Test-ProductDetailSurface $Xml)) {
        return $Xml
    }

    Write-Phase "leaving Swiggy product-detail surface for Home/search surface"
    $nextXml = $Xml
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 2
        $nextXml = Get-UiDump
        if (Test-CouponlessSuccessModal $nextXml) {
            $nextXml = Dismiss-CouponlessSuccessModal $nextXml
        }
        if (Test-HomeSearchSurface $nextXml) {
            return $nextXml
        }
        if (Test-CartSurface $nextXml) {
            return Exit-SwiggyCartToHome $nextXml
        }
        if (Test-ProductSearchSurface $nextXml) {
            return Exit-SwiggyProductSearchToHome $nextXml
        }
        if (-not (Test-ProductDetailSurface $nextXml)) {
            return $nextXml
        }
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
            $script:SavedHomeSelectionAttempted = $true
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
    $repairedOutOfServiceLocation = $false
    do {
        $xml = Get-UiDump
        if (Test-CouponlessSuccessModal $xml) {
            $xml = Dismiss-CouponlessSuccessModal $xml
            continue
        }
        if (Test-SwiggyMoneyWalletModal $xml) {
            $xml = Dismiss-SwiggyMoneyWalletModal $xml
            continue
        }
        if (Test-PreviousOrderRatingPrompt $xml) {
            $xml = Dismiss-PreviousOrderRatingPrompt $xml
            continue
        }
        if (Test-ClearCartUnserviceableModal $xml) {
            $xml = Clear-UnserviceableCartModal $xml
            continue
        }
        if (Confirm-OrderingForSelfIfPrompt $xml) {
            continue
        }
        if (Test-HomeSearchSurface $xml -TrustRecentSavedHomeSelection:$script:SavedHomeSelectionAttempted) {
            return $xml
        }
        if (Test-CheckoutPaymentSurface $xml) {
            $xml = Exit-SwiggyCartToHome $xml
            if (Test-HomeSearchSurface $xml -TrustRecentSavedHomeSelection:$script:SavedHomeSelectionAttempted) {
                return $xml
            }
        }
        if (Test-CartSurface $xml) {
            $xml = Exit-SwiggyCartToHome $xml
            if (Test-HomeSearchSurface $xml -TrustRecentSavedHomeSelection:$script:SavedHomeSelectionAttempted) {
                return $xml
            }
        }
        if (Test-ProductDetailSurface $xml) {
            $xml = Exit-SwiggyProductDetailToHome $xml
            if (Test-HomeSearchSurface $xml -TrustRecentSavedHomeSelection:$script:SavedHomeSelectionAttempted) {
                return $xml
            }
        }
        if (Test-ProductSearchSurface $xml) {
            $xml = Exit-SwiggyProductSearchToHome $xml
            if (Test-HomeSearchSurface $xml -TrustRecentSavedHomeSelection:$script:SavedHomeSelectionAttempted) {
                return $xml
            }
        }
        if ((Test-StoreUnavailable $xml) -and (Test-SelectedOutOfServiceLocation $xml) -and -not $repairedOutOfServiceLocation) {
            Write-Phase "selected Swiggy location is outside the service area; reopening address picker"
            $repairedOutOfServiceLocation = $true
            $xml = Open-AddressPicker $xml
            $xml = Dismiss-CouponlessSuccessModal $xml
            Select-SavedHomeAddress $xml
            continue
        }
        if (Test-BlockingStoreUnavailable $xml) {
            throw "Swiggy selected Home, but the app is showing a store-unavailable/high-traffic screen."
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    if ((Test-StoreUnavailable $xml) -and (Test-SelectedOutOfServiceLocation $xml)) {
        throw "Swiggy is still showing a store-unavailable screen after attempting to repair the selected out-of-service address."
    }
    if (Test-BlockingStoreUnavailable $xml) {
        throw "Swiggy selected Home, but the app is showing a store-unavailable/high-traffic screen."
    }

    throw "Swiggy did not reach a Home/search surface with the saved Home address selected."
}

function Invoke-SwiggyHomePreflight {
    Require-Device
    Require-Package $SwiggyPackage

    $xml = Start-SwiggyAndWait
    $xml = Dismiss-CouponlessSuccessModal $xml
    $xml = Dismiss-SwiggyMoneyWalletModal $xml
    $xml = Dismiss-PreviousOrderRatingPrompt $xml
    $xml = Clear-UnserviceableCartModal $xml
    $xml = Exit-StoreUnavailableToHome $xml
    if ((Test-StoreUnavailable $xml) -and (Test-SelectedOutOfServiceLocation $xml)) {
        Write-Phase "selected Swiggy location is outside the service area; reopening address picker"
        $xml = Open-AddressPicker $xml
        $xml = Dismiss-CouponlessSuccessModal $xml
        $xml = Dismiss-SwiggyMoneyWalletModal $xml
        $xml = Dismiss-PreviousOrderRatingPrompt $xml
        Select-SavedHomeAddress $xml
        Wait-SwiggyHomeReady | Out-Null
        Write-Phase "success: Swiggy Instamart is on saved Home and the Home/search surface."
        return
    }
    if (Test-StoreUnavailable $xml) {
        throw "Swiggy selected Home, but the app is showing a store-unavailable/high-traffic screen."
    }
    if (Test-HomeSearchSurface $xml) {
        Write-Phase "success: Swiggy Instamart is already on saved Home and the Home/search surface."
        return
    }

    if (Test-CheckoutPaymentSurface $xml) {
        $xml = Exit-SwiggyCartToHome $xml
        if (Test-HomeSearchSurface $xml) {
            Write-Phase "success: Swiggy Instamart left payment/checkout and reached the Home/search surface."
            return
        }
    }

    if (Test-CartSurface $xml) {
        $xml = Exit-SwiggyCartToHome $xml
        if (Test-HomeSearchSurface $xml) {
            Write-Phase "success: Swiggy Instamart left cart and reached the Home/search surface."
            return
        }
    }

    if (Test-ProductDetailSurface $xml) {
        $xml = Exit-SwiggyProductDetailToHome $xml
        if (Test-HomeSearchSurface $xml) {
            Write-Phase "success: Swiggy Instamart left product detail and reached the Home/search surface."
            return
        }
    }

    if (Test-ProductSearchSurface $xml) {
        $xml = Exit-SwiggyProductSearchToHome $xml
        if (Test-HomeSearchSurface $xml) {
            Write-Phase "success: Swiggy Instamart left product search and reached the Home/search surface."
            return
        }
    }

    if (-not (Test-AddressPicker $xml)) {
        $xml = Select-SwiggyHomeTab $xml
        $xml = Dismiss-CouponlessSuccessModal $xml
        $xml = Dismiss-SwiggyMoneyWalletModal $xml
        $xml = Dismiss-PreviousOrderRatingPrompt $xml
        $xml = Clear-UnserviceableCartModal $xml
        if (Test-HomeSearchSurface $xml) {
            Write-Phase "success: Swiggy Instamart is already on saved Home and the Home/search surface."
            return
        }
    }

    $xml = Open-AddressPicker $xml
    $xml = Dismiss-CouponlessSuccessModal $xml
    $xml = Dismiss-SwiggyMoneyWalletModal $xml
    $xml = Dismiss-PreviousOrderRatingPrompt $xml
    $xml = Clear-UnserviceableCartModal $xml
    Select-SavedHomeAddress $xml
    Wait-SwiggyHomeReady | Out-Null
    Write-Phase "success: Swiggy Instamart is on saved Home and the Home/search surface."
}

Invoke-SwiggyHomePreflight
