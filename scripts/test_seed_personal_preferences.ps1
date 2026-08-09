param()

$ErrorActionPreference = "Stop"

function Assert {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "TEST FAILED: $Message"
    }
}

$scriptDir = Split-Path -Parent $PSCommandPath
$profilePath = Join-Path $scriptDir "personal_preference_profile.example.json"

Write-Host "Test 1: validate + dry-run profile seed script output"
try {
    & "$scriptDir\seed_personal_preferences.ps1" -ProfilePath $profilePath -DryRun -ValidateOnly
Write-Host " - pass: valid profile validated"
} catch {
    throw "TEST FAILED: valid profile should pass validation. $($_.Exception.Message)"
}

Write-Host "Test 2: dry-run does not print stored product phrases"
$dryRunOutput = (& "$scriptDir\seed_personal_preferences.ps1" -ProfilePath $profilePath -DryRun 6>&1 | Out-String)
Assert ($dryRunOutput -match "preference broadcast prepared") "Expected a dry-run broadcast marker."
Assert (-not ($dryRunOutput -match "Impact Sugar Free")) "Dry-run must not print preferred product phrases."

$tempProfile = Join-Path $env:TEMP "personal_preference_profile_bad.json"
@'
[
  {"token":"bad", "preferredPhrase":"", "confidence": 0.9}
]
'@ | Set-Content -LiteralPath $tempProfile -Encoding UTF8

Write-Host "Test 3: validation failure for invalid profile should be non-zero"
$validationFailed = $false
try {
    & "$scriptDir\seed_personal_preferences.ps1" -ProfilePath $tempProfile -DryRun -ShowOnly | Out-Null
} catch {
    $validationFailed = $true
}
Assert $validationFailed "Expected invalid profile to fail validation."

Remove-Item -LiteralPath $tempProfile -ErrorAction SilentlyContinue

Write-Host "All tests passed."
