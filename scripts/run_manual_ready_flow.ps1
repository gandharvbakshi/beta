param(
    [string]$Instruction = "order pencil",
    [int]$TimeoutSeconds = 240,
    [string]$Package = "live.betaapp.android",
    [switch]$AllowFailedItems,
    [switch]$AllowStoreUnavailable,
    [switch]$AllowExternalAppUnresponsive
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
$ReceiverComponent = "$Package/com.example.beta.AutomationInstructionReceiver"
$LogsDir = Join-Path $ProjectDir "logs"
$ScenarioName = ($Instruction -replace '[^A-Za-z0-9_-]+', '_').Trim('_').ToLowerInvariant()
if (-not $ScenarioName) {
    $ScenarioName = "manual_ready_flow"
}
$RunStamp = Get-Date -Format "yyyyMMdd_HHmmss"
$ArtifactPrefix = "manual_${ScenarioName}_$RunStamp"
$FullLogPath = Join-Path $LogsDir "$ArtifactPrefix`_full_log.txt"
$CrashLogPath = Join-Path $LogsDir "$ArtifactPrefix`_crash_log.txt"
$FinalScreenPath = Join-Path $LogsDir "$ArtifactPrefix`_final_screen.png"
$SummaryPath = Join-Path $LogsDir "$ArtifactPrefix`_summary.json"

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null

function Write-Phase([string]$Message) {
    Write-Host "[$ScenarioName] $Message"
}

function Require-Device {
    $devices = adb devices | Select-String "`tdevice$"
    if (-not $devices) {
        throw "No connected emulator/device found. Prepare the emulator and rerun."
    }
}

function ConvertTo-AdbShellArg([string]$Value) {
    return ($Value -replace "\\", "\\\\" -replace "'", "\'" -replace " ", "\ " -replace "&", "\&" -replace ";", "\;")
}

function Get-FilteredLogText {
    $pattern = "AUTOMATION_INSTRUCTION_RECEIVED|AUTOMATION_INSTRUCTION_NO_SCREEN_SERVICE|INSTRUCTION_RECEIVED|MULTI_ORDER_STARTED|BLINKIT_SEARCH_STARTED|PARSED:|ITEM_RESULT|ORDER_RESULT|FLOW_FAILED|STATE: FAILED|checkout_boundary|store_unavailable|MediaProjection state: null|Cannot trigger screenshot: Service not capturing|ANR in live\.betaapp\.android|ANR in com\.grofers\.customerapp|ANR in in\.swiggy\.android\.instamart|Application Not Responding: in\.swiggy\.android\.instamart|in\.swiggy\.android\.instamart isn't responding|DeadSystemException"
    $matches = adb logcat -d -v time | Select-String $pattern
    if (-not $matches) {
        return ""
    }
    return ($matches | ForEach-Object { $_.ToString() }) -join "`n"
}

function Normalize-FlowItem([string]$Value) {
    $raw = if ($null -eq $Value) { "" } else { $Value }
    $normalized = $raw.Trim().ToLowerInvariant()
    $normalized = $normalized -replace "^(get me|pick up|order|buy|add|get|please|fetch|bring)\s+", ""
    $normalized = $normalized -replace "[^a-z0-9]+", ""
    if ($normalized -eq "apples") {
        return "apple"
    }
    return $normalized
}

function Get-LatestParsedItems([string]$LogText) {
    $parsedMatches = [regex]::Matches($LogText, 'PARSED:\s*([^\r\n]+)')
    if ($parsedMatches.Count -eq 0) {
        return @()
    }
    $latest = $parsedMatches[$parsedMatches.Count - 1].Groups[1].Value
    return @($latest -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Get-ItemResultRows([string]$LogText) {
    $rows = @()
    $itemMatches = [regex]::Matches($LogText, 'ITEM_RESULT\s+item="([^"]+)"\s+status=([a-z_]+)')
    foreach ($match in $itemMatches) {
        $rows += [pscustomobject]@{
            item = $match.Groups[1].Value
            status = $match.Groups[2].Value
        }
    }
    return $rows
}

function Get-MultiItemSequenceMismatch([string]$LogText) {
    $parsedItems = @(Get-LatestParsedItems $LogText)
    if ($parsedItems.Count -le 1) {
        return ""
    }

    $itemRows = @(Get-ItemResultRows $LogText)
    if ($itemRows.Count -lt $parsedItems.Count) {
        return "expected $($parsedItems.Count) item results, saw $($itemRows.Count)"
    }

    $tailRows = @($itemRows | Select-Object -Last $parsedItems.Count)
    for ($i = 0; $i -lt $parsedItems.Count; $i++) {
        $expected = Normalize-FlowItem $parsedItems[$i]
        $actual = Normalize-FlowItem $tailRows[$i].item
        if ($expected -ne $actual) {
            return "item $($i + 1) expected '$($parsedItems[$i])' but saw '$($tailRows[$i].item)'"
        }
    }

    return ""
}

function Resolve-ManualReadyOutcome([string]$LogText, [bool]$InstructionReceived) {
    if ($LogText -match "AUTOMATION_INSTRUCTION_NO_SCREEN_SERVICE") {
        return "no_screen_service"
    }
    if ($LogText -match "MediaProjection state: null|Cannot trigger screenshot: Service not capturing") {
        return "capture_lost"
    }
    if ($LogText -match "ANR in in\.swiggy\.android\.instamart|Application Not Responding: in\.swiggy\.android\.instamart|in\.swiggy\.android\.instamart isn't responding") {
        if ($AllowExternalAppUnresponsive) {
            return "external_app_unresponsive"
        }
        return "failed"
    }
    if ($LogText -match "ANR in live\.betaapp\.android|ANR in com\.grofers\.customerapp|DeadSystemException") {
        return "emulator_unresponsive"
    }
    if ($LogText -match "checkout_boundary") {
        return "checkout_boundary"
    }

    $multiOrderStarted = $LogText -match "MULTI_ORDER_STARTED"
    $orderMatches = [regex]::Matches($LogText, 'ORDER_RESULT\s+items_total=(\d+)\s+items_succeeded=(\d+)\s+items_failed=(\d+)\s+failures="([^"]*)"')
    if ($orderMatches.Count -gt 0) {
        $sequenceMismatch = Get-MultiItemSequenceMismatch $LogText
        if ($sequenceMismatch) {
            Write-Phase "item sequence mismatch: $sequenceMismatch"
            return "item_sequence_mismatch"
        }

        $last = $orderMatches[$orderMatches.Count - 1]
        if ([int]$last.Groups[3].Value -gt 0) {
            if ($AllowStoreUnavailable -and $last.Groups[4].Value -match "store_unavailable") {
                return "store_unavailable"
            }
            $isMultiItemResult = [int]$last.Groups[1].Value -gt 1
            if ($AllowFailedItems -and ($multiOrderStarted -or $isMultiItemResult)) {
                return "success_with_failed_items"
            }
            return "failed"
        }
        return "success"
    }

    if ($LogText -match "FLOW_FAILED|STATE: FAILED") {
        if ($multiOrderStarted) {
            return "received"
        }
        return "failed"
    }

    if (-not $InstructionReceived -and $LogText -match "AUTOMATION_INSTRUCTION_RECEIVED|INSTRUCTION_RECEIVED|BLINKIT_SEARCH_STARTED") {
        return "received"
    }
    return ""
}

function Save-Artifacts([string]$Outcome) {
    adb logcat -d > $FullLogPath
    adb logcat -d AndroidRuntime:E "*:S" > $CrashLogPath
    adb exec-out screencap -p > $FinalScreenPath
    [pscustomobject]@{
        instruction = $Instruction
        outcome = $Outcome
        timeout_seconds = $TimeoutSeconds
        full_log = $FullLogPath
        crash_log = $CrashLogPath
        final_screen = $FinalScreenPath
    } | ConvertTo-Json -Depth 4 | Set-Content -Path $SummaryPath -Encoding UTF8
}

Require-Device

Write-Phase "manual-ready mode: not launching apps, resetting cart, selecting address, or starting capture"
Write-Phase "clearing logcat"
adb logcat -c
Start-Sleep -Milliseconds 300

$escapedInstruction = ConvertTo-AdbShellArg $Instruction
Write-Phase "submitting instruction: $Instruction"
$broadcastOutput = adb shell "am broadcast -n $ReceiverComponent -a $Package.SUBMIT_AUTOMATION_INSTRUCTION --es instruction $escapedInstruction" 2>&1
$broadcastText = ($broadcastOutput | Out-String).Trim()
if ($broadcastText) {
    Write-Phase $broadcastText
    if ($broadcastText -match "(?i)inaccessible or not found|syntax error|no closing quote") {
        throw "ADB shell rejected part of the instruction. Escaped instruction: $escapedInstruction"
    }
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$printedLineCount = 0
$instructionReceived = $false
$outcome = ""

do {
    $logText = Get-FilteredLogText
    if ($logText) {
        $lines = $logText -split "`n"
        if ($lines.Count -gt $printedLineCount) {
            $lines[$printedLineCount..($lines.Count - 1)] | ForEach-Object {
                if ($_.Trim()) {
                    Write-Host $_
                }
            }
            $printedLineCount = $lines.Count
        }
        if ($logText -match "AUTOMATION_INSTRUCTION_RECEIVED|INSTRUCTION_RECEIVED|BLINKIT_SEARCH_STARTED") {
            $instructionReceived = $true
        }
        $resolved = Resolve-ManualReadyOutcome $logText $instructionReceived
        if ($resolved -and $resolved -ne "received") {
            $outcome = $resolved
            break
        }
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if (-not $outcome) {
    if ($instructionReceived) {
        $outcome = "timeout"
    } else {
        $outcome = "not_received"
    }
}

Save-Artifacts $outcome

if ($outcome -notin @("success", "success_with_failed_items", "store_unavailable", "external_app_unresponsive")) {
    throw "Manual-ready Blinkit flow failed for '$Instruction': $outcome. See $FullLogPath and $FinalScreenPath."
}

Write-Host "Manual-ready Blinkit flow passed for '$Instruction' with outcome '$outcome'."
Write-Host "Artifacts:"
Write-Host "  $FullLogPath"
Write-Host "  $FinalScreenPath"
