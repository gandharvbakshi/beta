from __future__ import annotations

import argparse
import tempfile
import textwrap
from pathlib import Path

from moviepy.editor import AudioFileClip, ImageClip, concatenate_videoclips
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "play_store_assets" / "accessibility_review"

SLIDES = [
    (
        "01_home.png",
        "Beta starts on its setup screen. The user chooses when to start grocery cart help.",
    ),
    (
        "02_prominent_disclosure.png",
        "Before setup, Beta shows a prominent disclosure explaining AccessibilityService and screen capture access, including visible name, precise delivery location, and delivery address.",
    ),
    (
        "03_accessibility_setup_prompt.png",
        "After Continue, Beta asks the user to open Android Accessibility settings. The user can still stop here with Not now.",
    ),
    (
        "04_android_accessibility_settings.png",
        "Android Settings shows Beta ordering assistant is off until the user chooses to enable it. Beta builds carts only after user setup.",
    ),
]


def _font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
        Path("C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def _wrap_text(text: str, font: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    current: list[str] = []
    for word in words:
        probe = " ".join([*current, word])
        if font.getbbox(probe)[2] <= max_width:
            current.append(word)
            continue
        if current:
            lines.append(" ".join(current))
        current = [word]
    if current:
        lines.append(" ".join(current))
    return lines


def _render_frame(source: Path, caption: str, index: int, total: int, output: Path) -> None:
    width, height = 720, 1600
    image = Image.open(source).convert("RGB").resize((width, height), Image.LANCZOS)
    draw = ImageDraw.Draw(image, "RGBA")

    title_font = _font(30, bold=True)
    caption_font = _font(28)
    small_font = _font(22)

    draw.rounded_rectangle((24, 28, width - 24, 112), radius=18, fill=(24, 29, 38, 214))
    draw.text((48, 44), "Beta AccessibilityService review", font=title_font, fill=(255, 255, 255, 255))
    draw.text((48, 80), f"Step {index} of {total}", font=small_font, fill=(222, 231, 245, 255))

    wrapped = _wrap_text(caption, caption_font, width - 96)
    wrapped = wrapped[:4]
    line_height = caption_font.getbbox("Ag")[3] + 14
    box_height = 52 + line_height * len(wrapped)
    top = height - box_height - 34
    draw.rounded_rectangle((24, top, width - 24, height - 34), radius=18, fill=(20, 24, 32, 224))
    y = top + 26
    for line in wrapped:
        draw.text((48, y), line, font=caption_font, fill=(255, 255, 255, 255))
        y += line_height

    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, "PNG")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=ASSET_DIR / "beta_accessibility_prominent_disclosure_review.mp4",
    )
    parser.add_argument("--audio", type=Path)
    parser.add_argument("--duration", type=float, default=7.5)
    args = parser.parse_args()

    missing = [name for name, _ in SLIDES if not (ASSET_DIR / name).exists()]
    if missing:
        raise SystemExit(f"Missing review frame(s): {', '.join(missing)}")

    audio_clip = AudioFileClip(str(args.audio)) if args.audio else None
    slide_duration = args.duration
    if audio_clip:
        slide_duration = max(args.duration, (audio_clip.duration + 1.0) / len(SLIDES))

    with tempfile.TemporaryDirectory(prefix="beta_accessibility_video_") as tmp:
        tmp_dir = Path(tmp)
        clips = []
        for i, (filename, caption) in enumerate(SLIDES, start=1):
            frame_path = tmp_dir / f"frame_{i:02d}.png"
            _render_frame(ASSET_DIR / filename, caption, i, len(SLIDES), frame_path)
            clips.append(ImageClip(str(frame_path)).set_duration(slide_duration))

        video = concatenate_videoclips(clips, method="compose")
        if audio_clip:
            video = video.set_audio(audio_clip.subclip(0, min(audio_clip.duration, video.duration)))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        video.write_videofile(
            str(args.output),
            fps=24,
            codec="libx264",
            audio=bool(audio_clip),
            audio_codec="aac" if audio_clip else None,
            preset="medium",
            ffmpeg_params=["-pix_fmt", "yuv420p", "-movflags", "+faststart"],
            verbose=False,
            logger=None,
        )
        video.close()
        if audio_clip:
            audio_clip.close()


if __name__ == "__main__":
    main()
