param(
    [Parameter(Mandatory = $true)]
    [string[]]$Queries,
    [Parameter(Mandatory = $true)]
    [string[]]$ExpectedProducts,
    [string]$DeviceSerial = "",
    [switch]$Execute,
    [switch]$LiveSearchPreflightOnly,
    [string]$SummaryDirectory = "",
    [int]$StabilitySeconds = 8,
    [string[]]$PreserveProducts = @(
        "Amul Unsalted Butter",
        "Amul Salted Butter"
    )
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
$LogsDir = if ($SummaryDirectory) { $SummaryDirectory } else { Join-Path $ProjectDir "logs" }
$RunStamp = Get-Date -Format "yyyyMMdd_HHmmss"
$ScriptName = "rollback_blinkit_search_items_$RunStamp"
$SummaryPath = Join-Path $LogsDir "$ScriptName`_summary.json"
$BlinkitPackage = "com.grofers.customerapp"
$TargetDecrementResourceId = "com.grofers.customerapp:id/icon_decrement"
$DeviceMode = $Execute.IsPresent -or $LiveSearchPreflightOnly.IsPresent

if ($Queries.Count -ne $ExpectedProducts.Count) {
    throw "Queries and ExpectedProducts must contain the same number of entries."
}

New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null

if (-not $DeviceMode) {
    Write-Host "[dry-run] execute mode disabled; showing planned actions only."
}

function ConvertTo-ExecutionTarget {
    if ($DeviceSerial) {
        return @("-s", $DeviceSerial)
    }
    return @()
}

function Invoke-DeviceCommand {
    param([string[]]$Arguments)
    if (-not $DeviceMode) {
        Write-Host "[dry-run] adb $($Arguments -join ' ')"
        return ""
    }
    $target = ConvertTo-ExecutionTarget
    if ($target.Count -gt 0) {
        return (& adb $target $Arguments) -join "`n"
    }
    return (& adb $Arguments) -join "`n"
}

function Get-FocusedBlinkitActivity {
    $windowState = Invoke-DeviceCommand @("shell", "dumpsys window")
    $focusLines = @($windowState -split "`n" | Where-Object {
        $_ -match "mCurrentFocus=|mFocusedApp="
    })
    return ($focusLines -join "`n")
}

function Get-ActiveDeviceSerial {
    if ($DeviceSerial) {
        return $DeviceSerial
    }
    if (-not $DeviceMode) {
        return "dry-run-device"
    }
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No connected device found. Provide -DeviceSerial or connect an emulator/device."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple devices detected. Provide -DeviceSerial to avoid ambiguity."
    }
    return ($devices[0].ToString() -split "`t")[0]
}

function Get-UiDump {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        Invoke-DeviceCommand @("shell", "rm -f /sdcard/window.xml") | Out-Null
        $dumpResult = (Invoke-DeviceCommand @("shell", "timeout 8 uiautomator dump /sdcard/window.xml")) -join "`n"
        if ($dumpResult -match "UI hierchary dumped to|UI hierarchy dumped to") {
            $xml = (Invoke-DeviceCommand @("shell", "cat /sdcard/window.xml")) -join "`n"
            if ($xml -match "<hierarchy") {
                return $xml
            }
        }
        Start-Sleep -Seconds 1
    }
    throw "Unable to capture UI hierarchy XML."
}

function Get-NodeAttr {
    param([string]$Node, [string]$Name)
    $pattern = '\b' + [regex]::Escape($Name) + '="([^"]*)"'
    $match = [regex]::Match($Node, $pattern)
    if (-not $match.Success) {
        return ""
    }
    return [System.Web.HttpUtility]::HtmlDecode($match.Groups[1].Value)
}

function Parse-Nodes {
    param([string]$Xml)
    $nodes = @()
    $matches = [regex]::Matches($Xml, "<node\b[^>]*>")

    foreach ($m in $matches) {
        $nodeText = $m.Value
        if ($nodeText -notmatch "bounds=") { continue }

        $bounds = Get-NodeAttr $nodeText "bounds"
        $boundsMatch = [regex]::Match($bounds, "\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
        if (-not $boundsMatch.Success) {
            continue
        }

        $x1 = [int]$boundsMatch.Groups[1].Value
        $y1 = [int]$boundsMatch.Groups[2].Value
        $x2 = [int]$boundsMatch.Groups[3].Value
        $y2 = [int]$boundsMatch.Groups[4].Value

        $nodes += [pscustomobject]@{
            Text = Get-NodeAttr $nodeText "text"
            Resource = Get-NodeAttr $nodeText "resource-id"
            Desc = Get-NodeAttr $nodeText "content-desc"
            Class = Get-NodeAttr $nodeText "class"
            Package = Get-NodeAttr $nodeText "package"
            Enabled = ((Get-NodeAttr $nodeText "enabled") -ne "false")
            Clickable = ((Get-NodeAttr $nodeText "clickable") -eq "true")
            X1 = $x1; Y1 = $y1; X2 = $x2; Y2 = $y2
            X = [int](($x1 + $x2) / 2)
            Y = [int](($y1 + $y2) / 2)
        }
    }

    return $nodes
}

function Assert-SafeBlinkitSearchSurface {
    param(
        [string]$ExpectedQuery = ""
    )

    $focus = Get-FocusedBlinkitActivity
    $searchComponent = "$BlinkitPackage/com.blinkit.quickdelivery.ui.screens.search.views.SearchActivity"
    if ($focus -notmatch [regex]::Escape($searchComponent)) {
        throw "Unsafe focus blocked: Blinkit SearchActivity is not focused."
    }
    if ($focus -match "(?i)cart|checkout|payment|nativeotp|otp") {
        throw "Unsafe focus blocked: cart, checkout, payment, or OTP activity detected."
    }

    $nodes = Parse-Nodes (Get-UiDump)
    $unsafeControls = @($nodes | Where-Object {
        $label = "$($_.Text) $($_.Desc) $($_.Resource)"
        $_.Enabled -and $label -match "(?i)place\s*order|checkout|confirm\s*and\s*pay|enter\s*otp|payment"
    })
    if ($unsafeControls.Count -gt 0) {
        throw "Unsafe surface blocked: checkout, payment, or OTP control is visible."
    }

    $searchInputs = @($nodes | Where-Object {
        $_.Package -eq $BlinkitPackage -and
        $_.Class -match "(?i)EditText" -and
        $_.Enabled -and
        ($_.Resource -match "(?i)(?:/id/|:id/).*edittext" -or $_.Resource -match "(?i)search")
    })
    if ($searchInputs.Count -ne 1) {
        throw "Unsafe search state blocked: expected exactly one enabled Blinkit search input, found $($searchInputs.Count)."
    }

    if ($ExpectedQuery) {
        $observedQuery = Normalize-Text "$($searchInputs[0].Text) $($searchInputs[0].Desc)"
        $expectedQueryNormalized = Normalize-Text $ExpectedQuery
        if ($observedQuery -ne $expectedQueryNormalized) {
            throw "Search query mismatch blocked: expected '$expectedQueryNormalized', observed '$observedQuery'."
        }
    }

    return $nodes
}

function Normalize-Text([string]$Value) {
    $v = ($Value -as [string]).ToLowerInvariant().Trim()
    $v = [regex]::Replace($v, "[^a-z0-9]+", " ")
    return ($v -replace "\s+", " ").Trim()
}

function Find-CardCount {
    param([object[]]$Nodes)

    $viewCartNodes = @($Nodes | Where-Object {
        "$($_.Text) $($_.Desc)" -match "(?i)\bview\s*cart\b"
    })

    if ($viewCartNodes.Count -eq 0) {
        return $null
    }

    $countMatches = @()
    foreach ($cartNode in $viewCartNodes) {
        $cartCenterY = $cartNode.Y
        $cartLeft = $cartNode.X1
        $cartRight = $cartNode.X2

        $neighbors = @($Nodes | Where-Object {
            $candidateText = "$($_.Text) $($_.Desc)"
            if ($candidateText -notmatch "(?i)\d+\s+items?\b") { return $false }

            $overlapY = [Math]::Max(
                0,
                [Math]::Min($_.Y2, ($cartNode.Y2 + 90)) - [Math]::Max($_.Y1, ($cartNode.Y1 - 90))
            )
            if ($overlapY -le 0) { return $false }
            if ([Math]::Abs($_.Y - $cartCenterY) -gt 130) { return $false }
            if ($_.X -lt ($cartLeft - 700) -or $_.X -gt ($cartRight + 700)) { return $false }

            return $true
        })

        foreach ($n in $neighbors) {
            $candidateText = "$($n.Text) $($n.Desc)"
            $match = [regex]::Match($candidateText, "(?i)(\d+)\s+items?\b")
            if ($match.Success) {
                $countMatches += [pscustomobject]@{
                    Count = [int]$match.Groups[1].Value
                    Cart = $cartNode
                    Source = $n
                }
            }
        }
    }

    if ($countMatches.Count -eq 0) {
        return $null
    }

    $deduplicated = @{}
    foreach ($candidate in $countMatches) {
        $key = @(
            $candidate.Count,
            $candidate.Cart.Resource,
            $candidate.Cart.X1, $candidate.Cart.Y1, $candidate.Cart.X2, $candidate.Cart.Y2,
            $candidate.Source.Resource,
            $candidate.Source.X1, $candidate.Source.Y1, $candidate.Source.X2, $candidate.Source.Y2
        ) -join '|'
        $deduplicated[$key] = $candidate
    }
    $uniqueMatches = @($deduplicated.Values)

    $distinctCountValues = $uniqueMatches | Group-Object Count | Where-Object { $_.Name -match '^\d+$' }
    if ($distinctCountValues.Count -ne 1) {
        throw "Unable to resolve an unambiguous cart count from View cart area. Found $($uniqueMatches.Count) distinct nearby count candidates."
    }

    if ($distinctCountValues[0].Count -ne 1) {
        throw "Multiple distinct nearby cart count nodes matched. Found $($distinctCountValues[0].Count) candidates for count $($distinctCountValues[0].Name)."
    }

    return [int]$distinctCountValues[0].Name
}

function Get-CartCount {
    $xml = Get-UiDump
    $nodes = Parse-Nodes $xml
    $fromNodes = Find-CardCount $nodes
    if ($null -ne $fromNodes) { return $fromNodes }

    $lines = [regex]::Matches($xml, 'text="([^"]*)"') | ForEach-Object { $_.Groups[1].Value }
    foreach ($line in $lines) {
        $match = [regex]::Match($line, '(?i)cart\s*\(?\s*(\d+)\s*\)?')
        if ($match.Success) { return [int]$match.Groups[1].Value }
    }
    return $null
}

function Exact-Product-Match([string]$Name, [string]$ExpectedProduct) {
    $nameNorm = Normalize-Text $Name
    $expectedNorm = Normalize-Text $ExpectedProduct
    if (-not $nameNorm -or -not $expectedNorm) { return $false }
    return $nameNorm -eq $expectedNorm
}

function Assert-NotPreserve([string]$ProductName) {
    $normalized = Normalize-Text $ProductName
    if (-not $normalized) {
        return
    }

    $preserved = $PreserveProducts | ForEach-Object { Normalize-Text $_ }
    foreach ($item in $preserved) {
        if (-not $item) { continue }
        if ($normalized -eq $item -or $normalized -like "*$item*" -or $item -like "*$normalized*") {
            throw "Matched product '$ProductName' is preserved and must not be decremented."
        }
    }
}

function Find-MatchingDecrement {
    param([object[]]$Nodes, [string]$Query, [string]$ExpectedProduct)

    $headerBoundaryY = 0
    $headerY = @($Nodes | Where-Object {
        $_.Package -eq $BlinkitPackage -and
        $_.Resource -match "(?i)(/header|/filter|/toolbar|/app_bar|/topbar|/top_bar|/search_bar)"
    } | ForEach-Object { $_.Y2 } | Measure-Object -Maximum).Maximum
    if ($null -ne $headerY) {
        $headerBoundaryY = [int]$headerY
    }

    $viewCartNodes = @($Nodes | Where-Object {
        $_.Package -eq $BlinkitPackage -and
        "$($_.Text) $($_.Desc) $($_.Resource)" -match "(?i)\bview\s*cart\b|:id/view_cart$"
    })
    if ($viewCartNodes.Count -eq 0) {
        throw "View cart footer was not found on the Blinkit search surface."
    }
    $viewCartTopY = [int](($viewCartNodes | Measure-Object -Property Y1 -Minimum).Minimum)

    $productNameIds = @(
        "${BlinkitPackage}:id/tv_name",
        "${BlinkitPackage}:id/tv_title",
        "${BlinkitPackage}:id/tvproductname",
        "${BlinkitPackage}:id/tv_product_name",
        "${BlinkitPackage}:id/product_name",
        "${BlinkitPackage}:id/producttitle",
        "${BlinkitPackage}:id/product_title"
    )

    $productMatches = @()
    foreach ($node in $Nodes) {
        $productText = "$($node.Text) $($node.Desc)"
        if (-not $productText.Trim()) { continue }
        if ($node.Class -match "(?i)EditText") { continue }
        if ($node.Package -ne $BlinkitPackage) { continue }
        if ($node.Y -le $headerBoundaryY) { continue }
        if ($node.Resource -notin $productNameIds) { continue }

        if (Exact-Product-Match $productText $ExpectedProduct) {
            $decrementNodes = @($Nodes | Where-Object {
                $_.Resource -eq $TargetDecrementResourceId -and
                $_.Package -eq $BlinkitPackage -and
                $_.Enabled -and
                $_.Y1 -gt $headerBoundaryY -and
                $_.Y2 -lt ($viewCartTopY - 24) -and
                $_.Y2 -lt 2200 -and
                $_.Y -ge ($node.Y - 450) -and
                $_.Y -le ($node.Y + 120) -and
                $_.X -ge $node.X -and
                $_.X -le $node.X + 420
            })

            if ($decrementNodes.Count -eq 1) {
                $productMatches += [pscustomobject]@{ Node = $node; Decrement = $decrementNodes[0] }
            } elseif ($decrementNodes.Count -gt 1) {
                throw "Multiple decrement controls matched product-card text for '$Query'. Failing closed before interaction."
            }
        }
    }

    if ($productMatches.Count -eq 0) {
        throw "No exact product-card text match found for '$ExpectedProduct' using query '$Query'."
    }

    if ($productMatches.Count -ne 1) {
        throw "Product-card match ambiguous for '$Query'. Found $($productMatches.Count) matches with an enabled decrement control."
    }

    $product = $productMatches[0].Node
    Assert-NotPreserve "$($product.Text) $($product.Desc)"

    return [pscustomobject]@{ Product = $product; Decrement = $productMatches[0].Decrement }
}

function Ensure-BlinkitForeground {
    Write-Host "Ensuring Blinkit foreground."
    $launcher = Invoke-DeviceCommand @(
        "shell",
        "cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $BlinkitPackage"
    )
    $launchActivity = "$BlinkitPackage/.DEFAULT"
    foreach ($line in ($launcher -split "`n")) {
        if ($line -match "(?i)^\s*$([regex]::Escape($BlinkitPackage))\/\S+") {
            $launchActivity = $matches[0].Trim()
            break
        }
    }

    Invoke-DeviceCommand @("shell", "am start -n $launchActivity") | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Invoke-DeviceCommand @("shell", "monkey -p $BlinkitPackage -c android.intent.category.LAUNCHER 1") | Out-Null
    }
    Start-Sleep -Seconds 1
}

function Wait-BlinkitSearchReadiness {
    param([int]$TimeoutSeconds = 6)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $nodes = Parse-Nodes (Get-UiDump)
        $hasSearch = Find-BlinkitSearchCandidates $nodes | Select-Object -First 1
        if ($hasSearch) {
            return $hasSearch
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for Blinkit search control readiness (${TimeoutSeconds}s)."
}

function Find-BlinkitSearchCandidates {
    param([object[]]$Nodes, [switch]$RequireInput)

    $editable = @($Nodes | Where-Object {
        $_.Package -eq $BlinkitPackage -and
        $_.Class -match "(?i)EditText" -and
        $_.Enabled -and
        ($_.Resource -match "(?i)(?:/id/|:id/).*edittext" -or $_.Resource -match "(?i)search" -or $_.Text -match "(?i)search" -or $_.Desc -match "(?i)search")
    })
    if ($editable.Count -gt 0) {
        return $editable
    }

    if (-not $RequireInput) {
        return @($Nodes | Where-Object {
            $_.Package -eq $BlinkitPackage -and
            $_.Clickable -and
            $_.Resource -match "(?i)search|query|search_query|search_box|search_bar|z_search_bar|search_bar_view_flipper|vsearch_parent|search_container|search_layout"
        } | Sort-Object Y)
    }
    return @()
}

function Open-BlinkitSearchBox {
    $null = Wait-BlinkitSearchReadiness
    $nodes = Parse-Nodes (Get-UiDump)
    $searchNodes = Find-BlinkitSearchCandidates $nodes -RequireInput
    $search = $searchNodes | Select-Object -First 1
    if (-not $search) {
        $search = Find-BlinkitSearchCandidates $nodes | Select-Object -First 1
    }

    if ($search -and $search.Class -match "(?i)EditText") {
        Write-Host "Search control candidate: x=$($search.X) y=$($search.Y) resource=$($search.Resource)"
        return $search
    }

    if (-not $search) {
        throw "Could not locate a safe Blinkit search control."
    }

    if ($search.Resource -match "(?i)view\s*cart|checkout|payment|pay") {
        throw "Search control candidate is too close to cart/checkout path."
    }

    Write-Host "Search control candidate: x=$($search.X) y=$($search.Y) resource=$($search.Resource)"
    Invoke-DeviceCommand @("shell", "input tap $($search.X) $($search.Y)")
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $afterNodes = Parse-Nodes (Get-UiDump)
        $searchAfter = (Find-BlinkitSearchCandidates $afterNodes -RequireInput) | Select-Object -First 1
        if ($searchAfter) {
            return $searchAfter
        }
        Start-Sleep -Milliseconds 400
    }

    throw "Search control tap did not reveal an editable Blinkit search field."
    return $search
}

function Clear-SearchInput {
    param([object]$SearchNode)

    $nodes = Parse-Nodes (Get-UiDump)
    $clear = $nodes | Where-Object {
        $_.Clickable -and
        ($_.Resource -match "(?i)clear|clear_text|ic_clear|delete|dismiss|cancel|iconCross" -or
            $_.Text -match "(?i)^clear(?: all)?$" -or
            $_.Desc -match "(?i)^clear(?: all)?$") -and
        $_.X -ge ($SearchNode.X1 - 240) -and $_.X -le ($SearchNode.X2 + 240) -and
        $_.Y -ge ($SearchNode.Y1 - 60) -and $_.Y -le ($SearchNode.Y2 + 120)
    } | Select-Object -First 1

    if ($clear) {
        Write-Host "Using clear button at x=$($clear.X) y=$($clear.Y)"
        Invoke-DeviceCommand @("shell", "input tap $($clear.X) $($clear.Y)")
        Start-Sleep -Milliseconds 250
        return
    }

    Invoke-DeviceCommand @("shell", "input keyevent 123")
    for ($i = 0; $i -lt 40; $i++) {
        Invoke-DeviceCommand @("shell", "input keyevent 67")
    }
}

function Search-Query {
    param([string]$Query)

    if (-not ($Query -as [string]) -or -not ($Query.Trim() -match "^[A-Za-z0-9 ]+$")) {
        throw "Unsafe or empty query blocked. Use alphanumeric words and spaces only."
    }

    $editText = Open-BlinkitSearchBox
    Clear-SearchInput $editText
    Start-Sleep -Milliseconds 300
    $encoded = $Query.Trim().Replace(" ", "%s")
    Invoke-DeviceCommand @("shell", "input text " + $encoded)
    Invoke-DeviceCommand @("shell", "input keyevent 4") | Out-Null
    Start-Sleep -Milliseconds 250
}

function Wait-MatchingDecrement {
    param([string]$Query, [string]$ExpectedProduct, [int]$TimeoutSeconds = 8)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = "No product result was available."
    while ((Get-Date) -lt $deadline) {
        try {
            $nodes = Parse-Nodes (Get-UiDump)
            return Find-MatchingDecrement $nodes $Query $ExpectedProduct
        }
        catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Milliseconds 500
        }
    }

    throw "Timed out waiting for an unambiguous decrement for '$Query'. Last check: $lastError"
}
$results = @()

if (-not $DeviceMode) {
    Write-Host "[dry-run] planned queries:"
    foreach ($q in $Queries) {
        Write-Host " - $q"
    }
    [pscustomobject]@{
        timestamp = (Get-Date).ToString('o')
        device = "dry-run"
        executed = $false
        queries = $Queries
        expected_products = $ExpectedProducts
        results = $results
    } | ConvertTo-Json -Depth 6 | Set-Content -Path $SummaryPath -Encoding UTF8
    exit 0
}

try {
    $resolvedDevice = Get-ActiveDeviceSerial
    $DeviceSerial = $resolvedDevice
    Write-Host "Using device: $DeviceSerial"

    for ($queryIndex = 0; $queryIndex -lt $Queries.Count; $queryIndex++) {
        $query = $Queries[$queryIndex]
        $expectedProduct = $ExpectedProducts[$queryIndex]
        Write-Host "`n=== Query: $query ==="
        Write-Host "Expected exact product: $expectedProduct"
        Ensure-BlinkitForeground
        Assert-SafeBlinkitSearchSurface | Out-Null
        Search-Query $query
        $pair = Wait-MatchingDecrement $query $expectedProduct

        $before = Get-CartCount
        if ($null -eq $before) {
            throw "Could not parse cart count for '$query' before decrement."
        }

        $plannedProductName = ($pair.Product.Text + ' ' + $pair.Product.Desc).Trim()
        $plannedBounds = "$($pair.Decrement.X1),$($pair.Decrement.Y1),$($pair.Decrement.X2),$($pair.Decrement.Y2)"

        $freshNodes = Assert-SafeBlinkitSearchSurface -ExpectedQuery $query
        $freshPair = Find-MatchingDecrement $freshNodes $query $expectedProduct
        $freshProductName = ($freshPair.Product.Text + ' ' + $freshPair.Product.Desc).Trim()
        $freshBounds = "$($freshPair.Decrement.X1),$($freshPair.Decrement.Y1),$($freshPair.Decrement.X2),$($freshPair.Decrement.Y2)"
        if ($freshProductName -ne $plannedProductName -or $freshBounds -ne $plannedBounds) {
            throw "Search result changed before decrement for '$query'. Failing closed before interaction."
        }

        $target = $freshPair.Decrement
        Write-Host "Target decrement for '$freshProductName' at ($($target.X),$($target.Y))"
        if ($LiveSearchPreflightOnly) {
            Write-Host "[live-search preflight] search UI checks passed; decrement was not tapped."
            $results += [ordered]@{
                query = $query
                status = 'preflight_passed'
                before_cart_count = $before
                decrement_x = $target.X
                decrement_y = $target.Y
                matched_product = $freshProductName
            }
            continue
        }

        Invoke-DeviceCommand @("shell", "input tap $($target.X) $($target.Y)")
        Start-Sleep -Seconds 2

        Assert-SafeBlinkitSearchSurface -ExpectedQuery $query | Out-Null

        $after = Get-CartCount
        if ($null -eq $after) {
            throw "Could not parse cart count for '$query' after decrement."
        }
        if (($before - $after) -ne 1) {
            throw "Cart count did not fall by one for '$query'. Before=$before After=$after"
        }

        if ($StabilitySeconds -gt 0) {
            Start-Sleep -Seconds $StabilitySeconds
            Assert-SafeBlinkitSearchSurface -ExpectedQuery $query | Out-Null
            $stableAfter = Get-CartCount
            if ($stableAfter -ne $after) {
                throw "Cart count was not stable after decrement for '$query'. Immediate=$after Stable=$stableAfter"
            }
        }

        $results += [ordered]@{
            query = $query
            status = 'success'
            before_cart_count = $before
            after_cart_count = $after
            decrement_x = $target.X
            decrement_y = $target.Y
            matched_product = $freshProductName
        }
    }

    Write-Host "Rollback sequence finished."

    [pscustomobject]@{
        timestamp = (Get-Date).ToString('o')
        device = $DeviceSerial
        executed = $Execute.IsPresent
        live_search_preflight_only = $LiveSearchPreflightOnly.IsPresent
        queries = $Queries
        expected_products = $ExpectedProducts
        results = $results
    } | ConvertTo-Json -Depth 6 | Set-Content -Path $SummaryPath -Encoding UTF8
}
catch {
    Write-Error $_.Exception.Message

    [pscustomobject]@{
        timestamp = (Get-Date).ToString('o')
        device = $DeviceSerial
        executed = $Execute.IsPresent
        live_search_preflight_only = $LiveSearchPreflightOnly.IsPresent
        queries = $Queries
        expected_products = $ExpectedProducts
        error = $_.Exception.Message
        results = $results
    } | ConvertTo-Json -Depth 6 | Set-Content -Path $SummaryPath -Encoding UTF8
    exit 1
}
