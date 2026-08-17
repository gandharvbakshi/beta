param(
    [Parameter(Mandatory = $true)]
    [string]$Adb,
    [Parameter(Mandatory = $true)]
    [string]$OutputFile,
    [Parameter(Mandatory = $true)]
    [string]$StderrFile,
    [string]$Mode = "filtered"
)

$ErrorActionPreference = "Continue"

$header = @(
    "Logcat capture started: $(Get-Date -Format o)",
    "adb: $Adb",
    "OutputFile: $OutputFile",
    "Mode: $Mode",
    ("=" * 100)
)
Set-Content -LiteralPath $OutputFile -Value $header -Encoding UTF8

try {
    & $Adb wait-for-device 2>> $StderrFile
} catch {
    Add-Content -LiteralPath $StderrFile -Value "wait-for-device failed: $($_.Exception.Message)"
}

function Quote-CmdArg([string]$Value) {
    '"' + $Value.Replace('"', '\"') + '"'
}

if ($Mode -eq "full") {
    $logcatArgs = @("logcat", "-v", "threadtime")
} else {
    $logcatArgs = @(
        "logcat",
        "-v", "threadtime",
        "BetaAgent:D",
        "BetaFeedback:D",
        "DebugLogger:W",
        "AndroidRuntime:E",
        "System.err:W",
        "*:S"
    )
}

# Use cmd redirection so adb bytes are appended as plain text instead of
# PowerShell 5's UTF-16 redirection format.
$cmdLine = "$(Quote-CmdArg $Adb) $($logcatArgs -join ' ') >> $(Quote-CmdArg $OutputFile) 2>> $(Quote-CmdArg $StderrFile)"
& cmd.exe /d /c $cmdLine
