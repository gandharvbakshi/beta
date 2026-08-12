[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DeviceSerial,

    [Parameter(Mandatory = $true)]
    [string]$Instruction,

    [Parameter(Mandatory = $true)]
    [int]$ExpectedItemCount,

    [string]$BackendKey = "stub-contract-key",
    [int]$Port = 8787,
    [switch]$BuildDebug,
    [switch]$InstallDebug,
    [switch]$SkipBroadcast
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Package = "live.betaapp.android"
$MainActivityComponent = "$Package/com.example.beta.MainActivity"
$ReceiverComponent = "$Package/com.example.beta.AutomationInstructionReceiver"
$ProjectDir = Split-Path -Parent $PSScriptRoot
$LogsDir = Join-Path $ProjectDir "logs"
$ScenarioName = "swiggy_mcp_contract"
$StubLog = Join-Path $LogsDir "swiggy-mcp-contract-stub.log"
$StubStdOut = Join-Path $LogsDir "swiggy-mcp-contract-stub.out.log"
$StubStdErr = Join-Path $LogsDir "swiggy-mcp-contract-stub.err.log"
$BaseUrl = "http://127.0.0.1:$Port"
$ApkPath = Join-Path $ProjectDir "app\build\outputs\apk\debug\app-debug.apk"

Set-Location $ProjectDir
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null

function Write-Phase([string]$Message) {
    Write-Host "[$ScenarioName] $Message"
}

function Invoke-Adb([string[]]$AdbArgs) {
    if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
        throw "DeviceSerial is required."
    }
    & adb -s $DeviceSerial @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: adb -s $DeviceSerial $($AdbArgs -join ' ')"
    }
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return $response
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Url"
}

function Start-StubServer {
    if (Test-Path $StubLog) { Remove-Item $StubLog -Force }
    if (Test-Path $StubStdOut) { Remove-Item $StubStdOut -Force }
    if (Test-Path $StubStdErr) { Remove-Item $StubStdErr -Force }

    $pythonArgs = @(
        "scripts\swiggy_mcp_contract_stub.py",
        "--host", "127.0.0.1",
        "--port", $Port,
        "--backend-key", $BackendKey,
        "--log-file", $StubLog
    )

    Write-Phase "starting debug-only Swiggy contract stub on $BaseUrl"
    $process = Start-Process -FilePath "python" -ArgumentList $pythonArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $StubStdOut -RedirectStandardError $StubStdErr
    Wait-Http "$BaseUrl/health" 20 | Out-Null
    return $process
}

function Stop-StubServer([System.Diagnostics.Process]$Process) {
    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Prepare-DebugBuild {
    if (-not $BuildDebug -and -not $InstallDebug) {
        return
    }

    $env:BETA_BACKEND_DEBUG_URL = $BaseUrl
    $env:BETA_BACKEND_API_KEY = $BackendKey
    Write-Phase "building debug APK against the localhost stub"
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Debug build failed."
    }
}

function Install-DebugBuild {
    if (-not $InstallDebug) {
        return
    }
    if (-not (Test-Path $ApkPath)) {
        throw "Debug APK not found at $ApkPath"
    }
    Write-Phase "installing debug APK"
    Invoke-Adb @("install", "-r", $ApkPath) | Out-Null
}

function Reverse-Port {
    Write-Phase "configuring adb reverse for localhost:$Port"
    Invoke-Adb @("reverse", "tcp:$Port", "tcp:$Port") | Out-Null
}

function Start-App {
    Write-Phase "resetting and foregrounding Beta for an isolated scenario"
    Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Adb @("logcat", "-c") | Out-Null
    Invoke-Adb @("shell", "am", "start", "-n", $MainActivityComponent) | Out-Null
    Start-Sleep -Seconds 3
}

function Submit-Instruction {
    if ($SkipBroadcast) {
        Write-Phase "broadcast skipped by request"
        return
    }
    $singleQuoteEscape = "'" + '"' + "'" + '"' + "'"
    $escapedInstruction = $Instruction.Replace("'", $singleQuoteEscape)
    $quotedInstruction = "'$escapedInstruction'"
    $shellCommand = "am broadcast -n $ReceiverComponent -a $Package.SUBMIT_AUTOMATION_INSTRUCTION --es instruction $quotedInstruction"
    Write-Phase "submitting exported debug broadcast"
    Invoke-Adb @("shell", $shellCommand) | Out-Null
}

function Get-UiDump {
    Invoke-Adb @("shell", "uiautomator", "dump", "/sdcard/window.xml") | Out-Null
    return (Invoke-Adb @("shell", "cat", "/sdcard/window.xml") | Out-String)
}

function Wait-ForPreview([int]$TimeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $logText = ""
        if (Test-Path $StubLog) {
            $logText = Get-Content $StubLog -Raw -ErrorAction SilentlyContinue
        }
        $deviceLog = (Invoke-Adb @("shell", "logcat", "-d") | Out-String)
        if ($deviceLog -match "Swiggy cart preview|Your Swiggy cart preview is ready|Found options for all") {
            return @{ Source = "logcat"; Text = $deviceLog }
        }
        if ($logText -match "/swiggy/cart/plan") {
            try {
                $xml = Get-UiDump
                if ($xml -match "Swiggy cart preview|Your Swiggy cart preview is ready") {
                    return @{ Source = "ui"; Text = $xml }
                }
            } catch {
                # Ignore transient UI dump failures and keep waiting.
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for the Swiggy preview state."
}

function Assert-NoCheckoutOrApply {
    $stubLog = if (Test-Path $StubLog) { Get-Content $StubLog -Raw } else { "" }
    if ($stubLog -match "/swiggy/cart/apply" -or $stubLog -match "checkout" -or $stubLog -match "clear") {
        throw "Safety failure: the stub recorded an apply/checkout/clear request."
    }

    function Test-RouteAbsent([string]$Path) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl$Path" -Headers @{ 'x-beta-backend-key' = $BackendKey } -Body '{}' -ContentType 'application/json' -TimeoutSec 5
            if ($response.StatusCode -ne 404) {
                throw "$Path unexpectedly exists."
            }
        } catch {
            $status = $null
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                $status = [int]$_.Exception.Response.StatusCode
            }
            if ($status -ne 404) {
                throw $_
            }
        }
    }

    Test-RouteAbsent "/swiggy/checkout"
    Test-RouteAbsent "/swiggy/clear"
}

function Assert-ExpectedItemCount {
    param(
        [string]$Text
    )

    if ($ExpectedItemCount -le 0) {
        return
    }

    $events = @(Get-Content $StubLog | ForEach-Object { $_ | ConvertFrom-Json })
    $batchEvent = @($events | Where-Object { $_.route -eq 'recommendations-batch' }) | Select-Object -Last 1
    $planEvent = @($events | Where-Object { $_.route -eq 'cart-plan' }) | Select-Object -Last 1
    if (-not $batchEvent -or @($batchEvent.body.queries).Count -ne $ExpectedItemCount) {
        throw "Expected $ExpectedItemCount batch queries, but the stub did not record that exact count."
    }
    if (-not $planEvent -or @($planEvent.body.requestedItems).Count -ne $ExpectedItemCount) {
        throw "Expected $ExpectedItemCount planned items, but the stub did not record that exact count."
    }
    Write-Phase "verified exact batch and plan item count: $ExpectedItemCount"
}

Write-Phase "debug-only and checkout-free contract harness"
Write-Phase "device=$DeviceSerial expectedItemCount=$ExpectedItemCount backendKey=$BackendKey"

$server = $null
try {
    $server = Start-StubServer
    Reverse-Port
    Prepare-DebugBuild
    Install-DebugBuild
    Start-App
    Submit-Instruction
    $preview = Wait-ForPreview
    Assert-ExpectedItemCount -Text $preview.Text
    Assert-NoCheckoutOrApply
    Write-Phase "success: preview reached with no apply/checkout paths"
}
finally {
    if ($null -ne $server) {
        Stop-StubServer $server
    }
}
