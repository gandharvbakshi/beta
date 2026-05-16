param(
    [string]$Instruction = "order pencil",
    [int]$TimeoutSeconds = 240,
    [string]$Package = "com.example.beta"
)

$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $PSScriptRoot
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
    return ($Value -replace "\\", "\\\\" -replace "'", "'\\''" -replace " ", "\ ")
}

function Get-FilteredLogText {
    $pattern = "AUTOMATION_INSTRUCTION_RECEIVED|AUTOMATION_INSTRUCTION_NO_SCREEN_SERVICE|INSTRUCTION_RECEIVED|BLINKIT_SEARCH_STARTED|PARSED:|ITEM_RESULT|ORDER_RESULT|FLOW_FAILED|STATE: FAILED|checkout_boundary|MediaProjection state: null|Cannot trigger screenshot: Service not capturing"
    $matches = adb logcat -d -v time | Select-String $pattern
    if (-not $matches) {
        return ""
    }
    return ($matches | ForEach-Object { $_.ToString() }) -join "`n"
}

function Resolve-ManualReadyOutcome([string]$LogText, [bool]$InstructionReceived) {
    if ($LogText -match "AUTOMATION_INSTRUCTION_NO_SCREEN_SERVICE") {
        return "no_screen_service"
    }
    if ($LogText -match "MediaProjection state: null|Cannot trigger screenshot: Service not capturing") {
        return "capture_lost"
    }
    if ($LogText -match "checkout_boundary") {
        return "checkout_boundary"
    }
    if ($LogText -match "FLOW_FAILED|STATE: FAILED") {
        return "failed"
    }

    $orderMatches = [regex]::Matches($LogText, 'ORDER_RESULT\s+items_total=(\d+)\s+items_succeeded=(\d+)\s+items_failed=(\d+)\s+failures="([^"]*)"')
    if ($orderMatches.Count -gt 0) {
        $last = $orderMatches[$orderMatches.Count - 1]
        if ([int]$last.Groups[3].Value -gt 0) {
            return "failed"
        }
        return "success"
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
$broadcastOutput = adb shell "am broadcast -n $Package/.AutomationInstructionReceiver -a com.example.beta.SUBMIT_AUTOMATION_INSTRUCTION --es instruction $escapedInstruction" 2>&1
$broadcastText = ($broadcastOutput | Out-String).Trim()
if ($broadcastText) {
    Write-Phase $broadcastText
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

if ($outcome -ne "success") {
    throw "Manual-ready Blinkit flow failed for '$Instruction': $outcome. See $FullLogPath and $FinalScreenPath."
}

Write-Host "Manual-ready Blinkit flow passed for '$Instruction'."
Write-Host "Artifacts:"
Write-Host "  $FullLogPath"
Write-Host "  $FinalScreenPath"
