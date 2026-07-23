"""Create title and label artwork for the Beta live-demo FFmpeg build."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


WIDTH = 1080
HEIGHT = 1920
BACKGROUND = (13, 24, 20)
PANEL = (247, 250, 246)
ACCENT = (31, 132, 78)
INK = (20, 31, 26)
MUTED = (77, 94, 85)


def font(size: int, *, bold: bool = False) -> ImageFont.FreeTypeFont:
    name = "segoeuib.ttf" if bold else "segoeui.ttf"
    candidates = [
        Path("C:/Windows/Fonts") / name,
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def wrapped_lines(
    draw: ImageDraw.ImageDraw,
    text: str,
    selected_font: ImageFont.ImageFont,
    max_width: int,
) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in text.split():
        candidate = f"{current} {word}".strip()
        left, _, right, _ = draw.textbbox((0, 0), candidate, font=selected_font)
        if current and right - left > max_width:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


def title_card(
    output: Path,
    eyebrow: str,
    title: str,
    body: str,
    *,
    closing: bool = False,
) -> None:
    image = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((72, 190, WIDTH - 72, HEIGHT - 190), radius=54, fill=PANEL)
    draw.ellipse((122, 270, 230, 378), fill=ACCENT)
    draw.text((176, 324), "β", font=font(54, bold=True), fill=(255, 255, 255), anchor="mm")
    draw.text((122, 440), eyebrow.upper(), font=font(34, bold=True), fill=ACCENT)

    title_font = font(74 if closing else 82, bold=True)
    y = 520
    for line in wrapped_lines(draw, title, title_font, WIDTH - 244):
        draw.text((122, y), line, font=title_font, fill=INK)
        y += 92

    body_font = font(40)
    y += 48
    for line in wrapped_lines(draw, body, body_font, WIDTH - 244):
        draw.text((122, y), line, font=body_font, fill=MUTED)
        y += 58

    draw.rounded_rectangle((122, HEIGHT - 410, WIDTH - 122, HEIGHT - 300), radius=30, fill=ACCENT)
    footer = "Designed for calm, confident ordering." if closing else "No checkout. No payment."
    draw.text(
        (WIDTH // 2, HEIGHT - 355),
        footer,
        font=font(36, bold=True),
        fill=(255, 255, 255),
        anchor="mm",
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output)


def label_image(output: Path, heading: str, detail: str) -> None:
    image = Image.new("RGBA", (WIDTH, 210), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((52, 20, WIDTH - 52, 194), radius=38, fill=(247, 250, 246, 242))
    draw.rounded_rectangle((78, 50, 94, 164), radius=8, fill=ACCENT)
    draw.text((126, 49), heading, font=font(39, bold=True), fill=INK)
    draw.text((126, 106), detail, font=font(31), fill=MUTED)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output)


def main() -> int:
    repo = Path(__file__).resolve().parents[1]
    cards = repo / "logs" / "demo" / "live_video_cards"
    title_card(
        cards / "intro.png",
        "Live Android proof of concept",
        "Beta helps older adults build a grocery cart by voice",
        "A real phone capture using Blinkit today, with Swiggy Instamart opening for the next integration step.",
    )
    title_card(
        cards / "outro.png",
        "What comes next",
        "Swiggy MCP will make the flow safer and more personal",
        "Server-side authentication, preference-aware choices, spoken cart diffs, and read-back verification.",
        closing=True,
    )
    label_image(cards / "butter_label.png", "Live Blinkit flow", "Searching for Amul salted butter • 100 g")
    label_image(
        cards / "butter_result_label.png",
        "Result from the same live take",
        "Exact 100 g pack • quantity 1 • cart verified",
    )
    label_image(
        cards / "vicks_label.png",
        "Live Blinkit flow",
        "Exact Vicks 25 ml reaches quantity 1, then stops safely",
    )
    label_image(
        cards / "coffee_label.png",
        "Live Blinkit flow",
        "Nescafe Classic coffee • 200 g • stalls safely",
    )
    label_image(
        cards / "swiggy_label.png",
        "Live Swiggy Instamart",
        "Instamart opens • demo stops before cart or checkout",
    )
    print(f"cards={cards}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
