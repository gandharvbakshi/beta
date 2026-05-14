param(
    [ValidateSet("single", "multi-clean", "multi-noisy", "quantity", "context")]
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
            Write-Host "Scenario 'multi-clean' is declared for future phases and is not implemented yet."
            return @()
        }
        "multi-noisy" {
            Write-Host "Scenario 'multi-noisy' is declared for future phases and is not implemented yet."
            return @()
        }
        "quantity" {
            Write-Host "Scenario 'quantity' is declared for future phases and is not implemented yet."
            return @()
        }
        "context" {
            Write-Host "Scenario 'context' is currently a placeholder for future phases."
            return @()
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
