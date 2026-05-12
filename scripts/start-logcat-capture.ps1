param(
    [string]$ProjectDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$OutputFile = "",
    [string]$PidFile = "",
    [switch]$FullLogcat
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    $candidates = @()

    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }

    $localProperties = Join-Path $ProjectDir "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        Get-Content -LiteralPath $localProperties | ForEach-Object {
            if ($_ -match "^sdk\.dir=(.+)$") {
                $sdkDir = $Matches[1].Replace("\:", ":").Replace("\\", "\")
                $candidates += (Join-Path $sdkDir "platform-tools\adb.exe")
            }
        }
    }

    $pathAdb = Get-Command adb -ErrorAction SilentlyContinue
    if ($pathAdb) {
        $candidates += $pathAdb.Source
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "adb.exe not found. Set ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir in local.properties."
}

$logDir = Join-Path $ProjectDir "frontend_logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not $OutputFile) {
    $OutputFile = Join-Path $logDir "latest_emulator.logcat.txt"
}
if (-not $PidFile) {
    $PidFile = Join-Path $logDir "latest_logcat.pid"
}
$OutputFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputFile)
$PidFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($PidFile)

if (Test-Path -LiteralPath $PidFile) {
    $oldPid = (Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($oldPid -match "^\d+$") {
        Stop-Process -Id ([int]$oldPid) -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

# Detached logcat can leave child cmd/adb processes holding the old file.
# Stop only processes whose command line references this repo's logcat files.
try {
    Get-CimInstance Win32_Process |
        Where-Object {
            ($_.Name -in @("adb.exe", "cmd.exe", "powershell.exe", "pwsh.exe")) -and
            (
                ($_.CommandLine -match [regex]::Escape("latest_emulator.logcat")) -or
                ($_.CommandLine -match [regex]::Escape("run-logcat-stream.ps1")) -or
                ($_.Name -eq "adb.exe" -and $_.CommandLine -match "\blogcat\b")
            )
        } |
        ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
    Start-Sleep -Milliseconds 750
} catch {
    # Non-fatal; if the file is still locked, later writes will report it.
}

$adb = Find-Adb

try {
    & $adb logcat -c 2>$null
} catch {
    # The emulator may not be online at build time. Keep going so capture starts
    # once adb can stream logs.
}

$stderrFile = Join-Path $logDir "latest_emulator.logcat.stderr.txt"
$stderrFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($stderrFile)
$streamScript = Join-Path $PSScriptRoot "run-logcat-stream.ps1"
$mode = if ($FullLogcat) { "full" } else { "filtered" }

Set-Content -LiteralPath $OutputFile -Value @(
    "Logcat capture initializing: $(Get-Date -Format o)",
    "Waiting for emulator/device if none is currently online.",
    ("=" * 100)
) -Encoding UTF8
Set-Content -LiteralPath $stderrFile -Value "" -Encoding UTF8

function Quote-CommandArg([string]$Value) {
    "'" + $Value.Replace("'", "''") + "'"
}

$shell = "powershell.exe"
$commandText = "& $(Quote-CommandArg $streamScript) -Adb $(Quote-CommandArg $adb) -OutputFile $(Quote-CommandArg $OutputFile) -StderrFile $(Quote-CommandArg $stderrFile) -Mode $(Quote-CommandArg $mode)"

$processId = $null
$commandLine = "$shell -NoProfile -ExecutionPolicy Bypass -Command ""$commandText"""

try {
    $startup = ([wmiclass]"Win32_ProcessStartup").CreateInstance()
    $startup.ShowWindow = 0
    $result = ([wmiclass]"Win32_Process").Create($commandLine, $ProjectDir, $startup)
    if ($result.ReturnValue -eq 0) {
        $processId = [int]$result.ProcessId
    }
} catch {
    $process = Start-Process `
        -FilePath $shell `
        -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $commandText) `
        -WorkingDirectory $ProjectDir `
        -WindowStyle Hidden `
        -PassThru
    $processId = $process.Id
}

if (-not $processId) {
    throw "Failed to start detached logcat capture process."
}

Set-Content -LiteralPath $PidFile -Value $processId -Encoding ASCII

$latest = @{
    output_file = $OutputFile
    stderr_file = $stderrFile
    pid_file = $PidFile
    pid = $processId
    started_at = (Get-Date -Format o)
    mode = $mode
}
$latest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $logDir "latest_logcat.json") -Encoding UTF8

Write-Host "Started logcat capture to $OutputFile (pid $processId)"
