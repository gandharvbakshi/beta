param(
    [string]$ProfilePath = (Join-Path $PSScriptRoot "personal_preference_profile.json"),
    [string]$Package = "live.betaapp.android",
    [string]$AndroidSerial = "",
    [switch]$DryRun,
    [switch]$ValidateOnly,
    [switch]$ClearBeforeSeed,
    [switch]$ShowOnly
)

$ErrorActionPreference = "Stop"

$ReceiverComponent = "$Package/com.example.beta.AutomationInstructionReceiver"
$SeedAction = "$Package.SEED_PREFERENCE"
$ClearAction = "$Package.CLEAR_PREFERENCES"

function Invoke-Adb {
    param([string[]]$Arguments)
    if ($script:DryRun) {
        Write-Host "[dry-run] preference broadcast prepared"
        return ""
    }
    if ($script:AndroidSerial -and $script:AndroidSerial.Trim()) {
        $Arguments = @("-s", $script:AndroidSerial.Trim()) + $Arguments
    }
    $result = & adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB preference broadcast failed. Device output: $($result -join ' ')"
    }
    return ($result -join "`n")
}

function Read-PreferenceProfile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Profile not found at '$Path'."
    }

    $raw = Get-Content -LiteralPath $Path -Raw
    if (-not $raw.Trim()) {
        throw "Profile file is empty: '$Path'."
    }

    $decoded = $raw | ConvertFrom-Json
    $entries = @()

    if ($null -eq $decoded) {
        throw "Profile is not valid JSON."
    } elseif ($decoded.PSObject.Properties.Name -contains "preferences") {
        $entries = @($decoded.preferences)
    } elseif ($decoded -is [System.Collections.IEnumerable] -and -not ($decoded -is [string])) {
        $entries = @($decoded)
    } else {
        throw "Profile must be either a JSON array or an object with a 'preferences' array."
    }

    if ($entries.Count -eq 0) {
        throw "Profile contains zero preferences."
    }

    return $entries
}

function Normalize-PreferenceEntry {
    param([pscustomobject]$Entry)
    $tokenRaw = ""
    $preferredRaw = ""
    $avoidRaw = @()
    $confidence = 1.0

    if ($Entry.PSObject.Properties.Name -contains "token") {
        $tokenRaw = [string]$Entry.token
    }
    if ($Entry.PSObject.Properties.Name -contains "preferredPhrase") {
        $preferredRaw = [string]$Entry.preferredPhrase
    }
    if ($Entry.PSObject.Properties.Name -contains "preferred_phrase") {
        $preferredRaw = [string]$Entry.preferred_phrase
    }

    if ($Entry.PSObject.Properties.Name -contains "avoidPhrases") {
        $avoidRaw = @($Entry.avoidPhrases)
    } elseif ($Entry.PSObject.Properties.Name -contains "avoid_phrases") {
        $avoidRaw = @($Entry.avoid_phrases)
    }

    if ($Entry.PSObject.Properties.Name -contains "confidence") {
        $confidence = [float]$Entry.confidence
    }

    if ([string]::IsNullOrWhiteSpace($tokenRaw) -or [string]::IsNullOrWhiteSpace($preferredRaw)) {
        throw "Each preference must include non-empty 'token' and 'preferredPhrase'."
    }

    if ($confidence -lt 0.0 -or $confidence -gt 1.0) {
        throw "Invalid confidence '$confidence' for token '$tokenRaw'. Confidence must be 0..1."
    }

    return [pscustomobject]@{
        token = $tokenRaw.Trim().ToLowerInvariant()
        preferredPhrase = $preferredRaw.Trim()
        avoidPhrases = $avoidRaw | ForEach-Object { [string]$_ | ForEach-Object { $_.Trim() } } | Where-Object { $_ -ne "" }
        confidence = $confidence
    }
}

function Validate-PreferenceEntries {
    param([psobject[]]$Entries)
    $normalized = New-Object System.Collections.Generic.List[object]
    $seenTokens = @{}

    foreach ($entry in $Entries) {
        $n = Normalize-PreferenceEntry -Entry $entry
        if ($seenTokens.ContainsKey($n.token)) {
            throw "Duplicate token in profile: '$($n.token)'. Keep one entry per token for deterministic behavior."
        }
        $seenTokens[$n.token] = $true
        $normalized.Add($n)
    }

    return $normalized.ToArray()
}

function ConvertTo-AndroidShellArgument {
    param([string]$Value)
    return "'" + $Value.Replace("'", "'`"'`"'") + "'"
}

function Send-SeedBroadcast {
    param([pscustomobject]$Item)

    $args = @(
        "shell", "am", "broadcast", "-n", $ReceiverComponent, "-a", $SeedAction,
        "--es", "token", (ConvertTo-AndroidShellArgument $Item.token),
        "--es", "preferred_phrase", (ConvertTo-AndroidShellArgument $Item.preferredPhrase),
        "--ef", "confidence", $Item.confidence.ToString([CultureInfo]::InvariantCulture)
    )

    if ($Item.avoidPhrases.Count -gt 0) {
        $args += @(
            "--esa",
            "avoid_phrases",
            (ConvertTo-AndroidShellArgument ($Item.avoidPhrases -join ","))
        )
    }

    if (-not $script:DryRun) {
        Invoke-Adb -Arguments $args | Out-Null
    } else {
        Invoke-Adb -Arguments $args | Out-Null
    }
}

function Seed-Prefs {
    param([pscustomobject[]]$Items)

    if ($script:ClearBeforeSeed -and -not $script:DryRun -and -not $script:ValidateOnly) {
        Write-Host "Clearing existing in-app preferences."
        Invoke-Adb -Arguments @("shell", "am", "broadcast", "-n", $ReceiverComponent, "-a", $ClearAction) | Out-Null
    }

    foreach ($item in $Items) {
        Send-SeedBroadcast -Item $item
        Write-Host "seeded preference token='$($item.token)'"
    }
}

Write-Host "Loading local preference profile."
$entries = Read-PreferenceProfile -Path $ProfilePath
$validated = Validate-PreferenceEntries -Entries $entries

Write-Host "Validated $($validated.Count) preference entr(y/ies)."
if ($ShowOnly -or $ValidateOnly) {
    Write-Host "Validation only requested. No broadcasts sent."
    return
}

Seed-Prefs -Items $validated
Write-Host "Done."
