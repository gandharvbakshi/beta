param(
    [string]$Voiceover = "logs\demo\beta_voiceover_ruhaan_normalized.wav",
    [string]$Output = "logs\demo\Beta_live_Blinkit_Swiggy_demo_with_Ruhaan.mp4",
    [switch]$ShowFullHeader
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$demo = Join-Path $repo "logs\demo"
$cards = Join-Path $demo "live_video_cards"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$work = Join-Path $demo "fast_render_$stamp"
New-Item -ItemType Directory -Path $work -Force | Out-Null

python (Join-Path $repo "scripts\create_live_demo_cards.py") | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Unable to create the demo title and label artwork."
}

$ffmpeg = python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"
if (-not $ffmpeg) {
    throw "Unable to locate FFmpeg through imageio_ffmpeg."
}

$headerStage = if ($ShowFullHeader) {
    "[base]null[private];"
} else {
    "color=c=0xf7faf6:s=573x205[privacy];[base][privacy]overlay=(W-w)/2:0[private];"
}

$phoneFilter = @"
[0:v]scale=573:1280:flags=lanczos[phone];
color=c=0x0d1814:s=720x1280[bg];
[bg][phone]overlay=(W-w)/2:0[base];
$headerStage
[1:v]scale=720:-1[label];
[private][label]overlay=0:H-h-12:shortest=1,format=yuv420p[out]
"@ -replace "`r`n", ""

function Write-VideoSegment {
    param(
        [string]$Source,
        [double]$Start,
        [double]$Duration,
        [string]$Label,
        [string]$Destination
    )

    & $ffmpeg -hide_banner -loglevel error -y `
        -ss $Start -t $Duration -i $Source `
        -loop 1 -i $Label `
        -filter_complex $phoneFilter -map "[out]" -an -t $Duration -r 24 `
        -c:v libx264 -preset veryfast -crf 19 -pix_fmt yuv420p $Destination
    if ($LASTEXITCODE -ne 0) {
        throw "FFmpeg failed while creating $Destination"
    }
}

function Write-ImageSegment {
    param(
        [string]$Source,
        [double]$Duration,
        [string]$Label,
        [string]$Destination
    )

    & $ffmpeg -hide_banner -loglevel error -y `
        -loop 1 -t $Duration -i $Source `
        -loop 1 -i $Label `
        -filter_complex $phoneFilter -map "[out]" -an -t $Duration -r 24 `
        -c:v libx264 -preset veryfast -crf 19 -pix_fmt yuv420p $Destination
    if ($LASTEXITCODE -ne 0) {
        throw "FFmpeg failed while creating $Destination"
    }
}

function Write-CardSegment {
    param(
        [string]$Source,
        [double]$Duration,
        [string]$Destination
    )

    & $ffmpeg -hide_banner -loglevel error -y `
        -loop 1 -t $Duration -i $Source `
        -vf "scale=720:1280:flags=lanczos,format=yuv420p" -an -t $Duration -r 24 `
        -c:v libx264 -preset veryfast -crf 19 -pix_fmt yuv420p $Destination
    if ($LASTEXITCODE -ne 0) {
        throw "FFmpeg failed while creating $Destination"
    }
}

$segments = @(
    (Join-Path $work "00_intro.mp4"),
    (Join-Path $work "01_butter_start.mp4"),
    (Join-Path $work "02_butter_results.mp4"),
    (Join-Path $work "03_butter_result.mp4"),
    (Join-Path $work "04_vicks.mp4"),
    (Join-Path $work "05_coffee.mp4"),
    (Join-Path $work "06_swiggy.mp4"),
    (Join-Path $work "07_outro.mp4")
)

Write-CardSegment `
    -Source (Join-Path $cards "intro.png") `
    -Duration 5 `
    -Destination $segments[0]
Write-VideoSegment `
    -Source (Join-Path $demo "recovered_01_butter.mp4") `
    -Start 0 `
    -Duration 2 `
    -Label (Join-Path $cards "butter_label.png") `
    -Destination $segments[1]
Write-VideoSegment `
    -Source (Join-Path $demo "recovered_01_butter.mp4") `
    -Start 8 `
    -Duration 8 `
    -Label (Join-Path $cards "butter_label.png") `
    -Destination $segments[2]
Write-ImageSegment `
    -Source (Join-Path $demo "live_01_butter_final.png") `
    -Duration 4 `
    -Label (Join-Path $cards "butter_result_label.png") `
    -Destination $segments[3]
Write-VideoSegment `
    -Source (Join-Path $demo "recovered_02_vicks.mp4") `
    -Start 8 `
    -Duration 29 `
    -Label (Join-Path $cards "vicks_label.png") `
    -Destination $segments[4]
Write-VideoSegment `
    -Source (Join-Path $demo "recovered_03_coffee.mp4") `
    -Start 0 `
    -Duration 12 `
    -Label (Join-Path $cards "coffee_label.png") `
    -Destination $segments[5]
Write-VideoSegment `
    -Source (Join-Path $demo "recovered_04_swiggy.mp4") `
    -Start 12 `
    -Duration 22 `
    -Label (Join-Path $cards "swiggy_label.png") `
    -Destination $segments[6]
Write-CardSegment `
    -Source (Join-Path $cards "outro.png") `
    -Duration 15 `
    -Destination $segments[7]

$concatList = Join-Path $work "segments.txt"
$segments | ForEach-Object {
    "file '$($_.Replace("'", "''"))'"
} | Set-Content -LiteralPath $concatList -Encoding ascii

$silentVideo = Join-Path $work "silent_concat.mp4"
& $ffmpeg -hide_banner -loglevel error -y `
    -f concat -safe 0 -i $concatList -c copy $silentVideo
if ($LASTEXITCODE -ne 0) {
    throw "FFmpeg failed while concatenating the live demo segments."
}

$voicePath = if ([IO.Path]::IsPathRooted($Voiceover)) {
    $Voiceover
} else {
    Join-Path $repo $Voiceover
}
$outputPath = if ([IO.Path]::IsPathRooted($Output)) {
    $Output
} else {
    Join-Path $repo $Output
}

& $ffmpeg -hide_banner -loglevel error -y `
    -i $silentVideo -i $voicePath `
    -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k `
    -shortest -movflags +faststart $outputPath
if ($LASTEXITCODE -ne 0) {
    throw "FFmpeg failed while adding narration to the final demo."
}

Write-Output "video=$outputPath"
Write-Output "work=$work"
