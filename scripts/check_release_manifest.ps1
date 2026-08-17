param(
    [string]$ManifestPath = "app/build/intermediates/bundle_manifest/release/processApplicationManifestReleaseForBundle/AndroidManifest.xml"
)

$ErrorActionPreference = "Stop"

$resolvedManifest = Resolve-Path -LiteralPath $ManifestPath -ErrorAction Stop
$manifest = Get-Content -Raw -LiteralPath $resolvedManifest
$forbidden = @(
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "MyAccessibilityService",
    "ScreenCaptureService",
    "com.grofers.customerapp",
    "com.zeptoconsumerapp",
    "com.zepto.customer"
)

$found = @($forbidden | Where-Object { $manifest.Contains($_) })
if ($found.Count -gt 0) {
    throw "Release manifest contains forbidden legacy or advertising declarations: $($found -join ', ')"
}

Write-Host "Release manifest gate passed: no legacy screen-access/provider or advertising-ID declarations found."
