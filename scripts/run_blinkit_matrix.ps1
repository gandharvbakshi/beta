param(
    [ValidateSet("single", "multi-clean", "multi-noisy", "quantity", "context", "preflight", "substitution", "evidence")]
    [string]$Scenario = "single",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
$FixturePath = Join-Path $PSScriptRoot "test_fixtures\blinkit_probe_products.json"
$LogsDir = Join-Path $ProjectDir "logs"
$MatrixTimestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$MatrixPath = Join-Path $LogsDir "matrix_${Scenario}_$MatrixTimestamp.csv"
$RunnerPath = Join-Path $PSScriptRoot "run_blinkit_flow_test.ps1"

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null

if (-not (Test-Path -LiteralPath $FixturePath)) {
    throw "Missing fixture file: $FixturePath"
}

$Fixture = Get-Content -Raw $FixturePath | ConvertFrom-Json
$FixtureOnlyScenarios = @("preflight", "substitution", "evidence")

function Get-FixtureScenarioInstructions {
    param([string]$ScenarioName)

    $scenarioGroup = $Fixture.matrix_scenarios
    if ($scenarioGroup) {
        $scenarioProperty = $scenarioGroup.PSObject.Properties[$ScenarioName]
        if ($scenarioProperty) {
            return @($scenarioProperty.Value)
        }
    }
    return @()
}

function Get-ScenarioInstructions {
    param([string]$ScenarioName)

    switch ($ScenarioName) {
        "single" {
            if (-not $Fixture.in_stock_common) {
                Write-Warning "Fixture is missing in_stock_common entries."
                return @()
            }
            return @($Fixture.in_stock_common | ForEach-Object { "order $_" })
        }
        "multi-clean" {
            $fixtureInstructions = Get-FixtureScenarioInstructions $ScenarioName
            if ($fixtureInstructions.Count -gt 0) { return $fixtureInstructions }
            return @(
                "order $($Fixture.in_stock_common[0]), $($Fixture.in_stock_common[1]), $($Fixture.in_stock_common[2])",
                "order $($Fixture.in_stock_common[0]), $($Fixture.in_stock_common[1]), $($Fixture.in_stock_common[2]), $($Fixture.in_stock_variant_heavy[0]), $($Fixture.in_stock_common[3])"
            )
        }
        "multi-noisy" {
            $fixtureInstructions = Get-FixtureScenarioInstructions $ScenarioName
            if ($fixtureInstructions.Count -gt 0) { return $fixtureInstructions }
            return @(
                "butter , and apple, and maybe notebook",
                "order butter apple and notebook",
                "please buy   butter,,, apple ;; notebook"
            )
        }
        "quantity" {
            $fixtureInstructions = Get-FixtureScenarioInstructions $ScenarioName
            if ($fixtureInstructions.Count -gt 0) { return $fixtureInstructions }
            return @(
                "2 butter",
                "6 apples",
                "500 g bhindi",
                "500 g bhindi, 2 butter, 6 apples"
            )
        }
        "context" {
            $fixtureInstructions = Get-FixtureScenarioInstructions $ScenarioName
            if ($fixtureInstructions.Count -gt 0) { return $fixtureInstructions }
            return @(
                "order butter with my usual preference",
                "order apple without the sour ones",
                "order notebook as-is"
            )
        }
        "preflight" {
            $fixtureInstructions = Get-FixtureScenarioInstructions $ScenarioName
            if ($fixtureInstructions.Count -gt 0) { return $fixtureInstructions }
            return @(
                "fixture-only: emulator preflight skips location-distance checks",
                "fixture-only: production preflight blocks risky location states"
            )
        }
        "substitution" {
            $fixtureInstructions = Get-FixtureScenarioInstructions $ScenarioName
            if ($fixtureInstructions.Count -gt 0) { return $fixtureInstructions }
            return @(
                "fixture-only: review out-of-stock alternatives before adding",
                "fixture-only: accept only the non-oos substitute"
            )
        }
        "evidence" {
            $fixtureInstructions = Get-FixtureScenarioInstructions $ScenarioName
            if ($fixtureInstructions.Count -gt 0) { return $fixtureInstructions }
            return @(
                "fixture-only: log accessibility evidence first",
                "fixture-only: fall back to OCR only when accessibility is missing",
                "fixture-only: use screenshot/model evidence as the last resort"
            )
        }
    }
}

function Get-SanitizedScenarioToken {
    param([string]$Instruction)

    $ScenarioName = ($Instruction -replace '[^A-Za-z0-9_-]+', '_').Trim('_').ToLowerInvariant()
    if (-not $ScenarioName) {
        return "blinkit_flow"
    }
    return $ScenarioName
}

function Parse-OrderResult {
    param([string]$Text)

    $pattern = 'ORDER_RESULT\s+items_total=(\d+)\s+items_succeeded=(\d+)\s+items_failed=(\d+)\s+failures="([^"]*)"'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        return $null
    }
    return [pscustomobject]@{
        items_total = [int]$match.Groups[1].Value
        items_succeeded = [int]$match.Groups[2].Value
        items_failed = [int]$match.Groups[3].Value
        failures = $match.Groups[4].Value
    }
}

function Resolve-OrderResult {
    param([string]$LogPath)

    if (Test-Path -LiteralPath $LogPath) {
        $direct = Parse-OrderResult (Get-Content -Raw $LogPath)
        if ($direct) {
            return $direct
        }
    }

    $logcatText = adb logcat -d | Select-String "ORDER_RESULT" -SimpleMatch | ForEach-Object { $_.ToString() } | Out-String
    return Parse-OrderResult $logcatText
}

function Get-ResultRow {
    param([string]$Instruction, [double]$DurationSeconds, [pscustomobject]$OrderResult, [string]$Failure)

    if (-not $OrderResult) {
        return [pscustomobject]@{
            instruction = $Instruction
            items_total = ""
            items_succeeded = ""
            items_failed = ""
            duration_s = [string]([math]::Round($DurationSeconds, 2))
            failures = $Failure
        }
    }

    $failures = $OrderResult.failures
    if ($Failure) {
        if ($failures) {
            $failures = "$Failure; $failures"
        } else {
            $failures = $Failure
        }
    }

    return [pscustomobject]@{
        instruction = $Instruction
        items_total = $OrderResult.items_total
        items_succeeded = $OrderResult.items_succeeded
        items_failed = $OrderResult.items_failed
        duration_s = [string]([math]::Round($DurationSeconds, 2))
        failures = $failures
    }
}

$instructions = Get-ScenarioInstructions -ScenarioName $Scenario
if (-not $instructions -or $instructions.Count -eq 0) {
    @(
        [pscustomobject]@{
            instruction = ""
            items_total = ""
            items_succeeded = ""
            items_failed = ""
            duration_s = ""
            failures = "no_instructions"
        }
    ) | Export-Csv -Path $MatrixPath -NoTypeInformation -Encoding UTF8
    Write-Host "Matrix run completed: $MatrixPath"
    return
}

$rows = New-Object System.Collections.Generic.List[object]

foreach ($instruction in $instructions) {
    if ($FixtureOnlyScenarios -contains $Scenario) {
        $rows.Add((Get-ResultRow -Instruction $instruction -DurationSeconds 0 -OrderResult $null -Failure "fixture_only"))
        continue
    }

    $token = Get-SanitizedScenarioToken $instruction
    $fullLogPath = Join-Path $LogsDir "$token`_full_log.txt"
    Write-Host "Running scenario '$Scenario' instruction: $instruction"

    $start = Get-Date
    $runFailedMessage = ""
    try {
        if ($SkipBuild) {
            & $RunnerPath -Instruction $instruction -SkipBuild
        } else {
            & $RunnerPath -Instruction $instruction
        }
    }
    catch {
        $runFailedMessage = "script_failed:$($_.Exception.Message)"
    }
    $duration = (Get-Date) - $start
    $durationSeconds = $duration.TotalSeconds

    $orderResult = Resolve-OrderResult -LogPath $fullLogPath
    if (-not $orderResult -and -not $runFailedMessage) {
        $runFailedMessage = "ORDER_RESULT_not_found"
    }

    $rows.Add((Get-ResultRow -Instruction $instruction -DurationSeconds $durationSeconds -OrderResult $orderResult -Failure $runFailedMessage))
}

$rows | Export-Csv -Path $MatrixPath -NoTypeInformation -Encoding UTF8
Write-Host "Matrix run completed: $MatrixPath"
