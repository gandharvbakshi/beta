"""Inspect or publish the Beta Play release through a short-lived edit.

The script keeps the package fixed to ``live.betaapp.android`` and uses a
service-account JSON supplied on the command line or through the
``BETA_PLAY_PUBLISHER_JSON`` environment variable. It supports:

* ``inspect``: create an ephemeral edit and print the current tracks, bundles,
  localized listings, app details, and screenshot URLs.
* ``publish``: verify the AAB version code is unused, upload the bundle,
  update the en-US/en-GB listings, replace screenshot sets inside the same
  edit, validate the edit, and commit once.

No credentials are printed. If a commit fails after the request may already
have reached Play, the edit is preserved for manual inspection.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
from typing import Iterator

try:
    from PIL import Image
except Exception:  # pragma: no cover - Pillow is expected but not required.
    Image = None

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload


SCOPE = "https://www.googleapis.com/auth/androidpublisher"
PACKAGE_NAME = "live.betaapp.android"
TRACK_NAME = "beta"
LISTING_LOCALES = ("en-US", "en-GB")
ALLOWED_IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg"}
FEATURE_GRAPHIC_SIZE = (1024, 500)
SCREENSHOT_DIR_TO_IMAGE_TYPE = {
    "phone": "phoneScreenshots",
    "phone_screenshots": "phoneScreenshots",
    "tablet_7in": "sevenInchScreenshots",
    "seven_inch": "sevenInchScreenshots",
    "tablet_10in": "tenInchScreenshots",
    "ten_inch": "tenInchScreenshots",
}
IMAGE_TYPES = tuple(dict.fromkeys(SCREENSHOT_DIR_TO_IMAGE_TYPE.values()))
RELEASE_NAME = "0.3.0"
RELEASE_NOTES_BY_LOCALE = {
    "en-US": (
        "Swiggy checkout review is clearer, with the full cart, address, fees, "
        "total, and payment method shown before the final confirmation. Voice "
        "and text still work together, and interrupted payment recovery is safer "
        "so you can return to the same review."
    ),
    "en-GB": (
        "Swiggy checkout review is clearer, with the full cart, address, fees, "
        "total, and payment method shown before the final confirmation. Voice "
        "and text still work together, and interrupted payment recovery is safer "
        "so you can return to the same review."
    ),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--credentials",
        default=os.environ.get("BETA_PLAY_PUBLISHER_JSON", ""),
        help="Path to the Google Play Android Publisher service-account JSON.",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("inspect", parents=[common], help="Read the current draft edit state.")

    publish = subparsers.add_parser(
        "publish",
        parents=[common],
        help="Upload a release bundle, listing updates, and screenshots.",
    )
    publish.add_argument("--aab", required=True, help="Path to the Android App Bundle.")
    publish.add_argument(
        "--version-code",
        type=int,
        required=True,
        help="Verified bundle version code to check before upload.",
    )
    publish.add_argument(
        "--listing-dir",
        required=True,
        help="Directory containing en-US.json and en-GB.json listing drafts.",
    )
    publish.add_argument(
        "--feature-graphic",
        help="Optional 1024x500 PNG or JPG feature graphic to upload for both locales.",
    )
    publish.add_argument(
        "--screenshots-dir",
        required=True,
        help=(
            "Directory containing screenshot subdirectories such as phone, "
            "tablet_7in, and tablet_10in."
        ),
    )

    return parser.parse_args()


def build_publisher(credentials_path: Path):
    credentials = service_account.Credentials.from_service_account_file(
        str(credentials_path),
        scopes=[SCOPE],
    )
    return build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)


def require_file(path: Path, description: str) -> Path:
    if not path.is_file():
        raise SystemExit(f"{description} not found: {path}")
    return path


def require_dir(path: Path, description: str) -> Path:
    if not path.is_dir():
        raise SystemExit(f"{description} not found: {path}")
    return path


def create_edit(publisher):
    return publisher.edits().insert(packageName=PACKAGE_NAME, body={}).execute()["id"]


def delete_edit(publisher, edit_id: str) -> None:
    publisher.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def print_json_summary(prefix: str, payload: object) -> None:
    print(f"{prefix}={json.dumps(payload, sort_keys=True, ensure_ascii=False)}")


def list_tracks(publisher, edit_id: str) -> list[dict]:
    response = publisher.edits().tracks().list(
        packageName=PACKAGE_NAME,
        editId=edit_id,
    ).execute()
    return response.get("tracks") or []


def list_bundles(publisher, edit_id: str) -> list[dict]:
    response = publisher.edits().bundles().list(
        packageName=PACKAGE_NAME,
        editId=edit_id,
    ).execute()
    return response.get("bundles") or []


def list_listings(publisher, edit_id: str) -> list[dict]:
    response = publisher.edits().listings().list(
        packageName=PACKAGE_NAME,
        editId=edit_id,
    ).execute()
    return response.get("listings") or []


def get_details(publisher, edit_id: str) -> dict:
    return publisher.edits().details().get(
        packageName=PACKAGE_NAME,
        editId=edit_id,
    ).execute()


def list_images(publisher, edit_id: str, language: str, image_type: str) -> list[dict]:
    try:
        response = publisher.edits().images().list(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            language=language,
            imageType=image_type,
        ).execute()
    except HttpError as exc:
        status = getattr(getattr(exc, "resp", None), "status", None)
        if status in {404, 400}:
            return []
        raise
    return response.get("images") or []


def describe_tracks(publisher, edit_id: str) -> None:
    for track in list_tracks(publisher, edit_id):
        releases = track.get("releases") or []
        version_codes = sorted(
            {str(code) for release in releases for code in (release.get("versionCodes") or [])}
        )
        print(
            "track="
            f"{track.get('track', '')} "
            f"releases={len(releases)} "
            f"version_codes={','.join(version_codes)}"
        )
        for release in releases:
            print(
                "  release="
                f"status:{release.get('status', 'unknown')} "
                f"versionCodes:{','.join(release.get('versionCodes') or [])} "
                f"userFraction:{release.get('userFraction')}"
            )


def describe_bundles(publisher, edit_id: str) -> None:
    for bundle in list_bundles(publisher, edit_id):
        print(
            "bundle="
            f"versionCode:{bundle.get('versionCode')} "
            f"sha1:{bundle.get('sha1', '')} "
            f"sha256:{bundle.get('sha256', '')}"
        )


def describe_listings(publisher, edit_id: str) -> None:
    for listing in list_listings(publisher, edit_id):
        short_description = listing.get("shortDescription", "")
        full_description = listing.get("fullDescription", "")
        print(
            "listing="
            f"language:{listing.get('language', '')} "
            f"title:{listing.get('title', '')} "
            f"short_len:{len(short_description)} "
            f"full_len:{len(full_description)}"
        )


def describe_details(publisher, edit_id: str) -> None:
    print_json_summary("details", get_details(publisher, edit_id))


def describe_images(publisher, edit_id: str) -> None:
    for language in LISTING_LOCALES:
        for image_type in IMAGE_TYPES:
            images = list_images(publisher, edit_id, language, image_type)
            urls = [image.get("url", "") for image in images]
            print(
                "images="
                f"language:{language} "
                f"type:{image_type} "
                f"count:{len(images)} "
                f"urls:{','.join(urls)}"
            )


def read_existing_version_codes(publisher, edit_id: str) -> set[str]:
    version_codes: set[str] = set()
    for track in list_tracks(publisher, edit_id):
        for release in track.get("releases") or []:
            version_codes.update(str(code) for code in (release.get("versionCodes") or []))
    for bundle in list_bundles(publisher, edit_id):
        if "versionCode" in bundle:
            version_codes.add(str(bundle["versionCode"]))
    return version_codes


def ensure_version_is_unused(publisher, edit_id: str, version_code: int) -> None:
    existing = read_existing_version_codes(publisher, edit_id)
    if str(version_code) in existing:
        raise SystemExit(f"Version code {version_code} is already present in this edit.")


def load_listing_payloads(listing_dir: Path) -> dict[str, dict]:
    payloads: dict[str, dict] = {}
    for locale in LISTING_LOCALES:
        payload = load_json(require_file(listing_dir / f"{locale}.json", f"{locale} listing"))
        validate_listing_payload(locale, payload)
        payloads[locale] = payload
    return payloads


def validate_listing_payload(locale: str, payload: dict) -> None:
    title = str(payload.get("title", "")).strip()
    short_description = str(payload.get("shortDescription", "")).strip()
    full_description = str(payload.get("fullDescription", "")).strip()
    if not title or len(title) > 30:
        raise SystemExit(f"{locale} title must be 1..30 characters.")
    if not short_description or len(short_description) > 80:
        raise SystemExit(f"{locale} shortDescription must be 1..80 characters.")
    if not full_description or len(full_description) > 4000:
        raise SystemExit(f"{locale} fullDescription must be 1..4000 characters.")


def validate_feature_graphic(feature_graphic: Path) -> None:
    require_file(feature_graphic, "Feature graphic")
    if feature_graphic.suffix.lower() not in ALLOWED_IMAGE_EXTENSIONS:
        raise SystemExit("Feature graphic must be a PNG or JPG file.")
    width, height = image_dimensions(feature_graphic)
    if (width, height) != FEATURE_GRAPHIC_SIZE:
        raise SystemExit(
            f"Feature graphic must be exactly {FEATURE_GRAPHIC_SIZE[0]}x{FEATURE_GRAPHIC_SIZE[1]} pixels."
        )


def image_dimensions(image_path: Path) -> tuple[int, int]:
    if Image is not None:
        with Image.open(image_path) as image:
            return image.size
    if image_path.suffix.lower() != ".png":
        raise SystemExit("Pillow is required to validate non-PNG feature graphics.")
    with image_path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise SystemExit("The PNG feature graphic could not be read.")
    width = int.from_bytes(header[16:20], "big")
    height = int.from_bytes(header[20:24], "big")
    return width, height


def update_listings(publisher, edit_id: str, listing_payloads: dict[str, dict]) -> None:
    for locale, payload in listing_payloads.items():
        publisher.edits().listings().patch(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            language=locale,
            body=payload,
        ).execute()
        print(
            "listing_update="
            f"language:{locale} "
            f"title:{payload.get('title', '')} "
            f"short_len:{len(payload.get('shortDescription', ''))} "
            f"full_len:{len(payload.get('fullDescription', ''))}"
        )


def iter_screenshot_directories(screenshots_dir: Path) -> Iterator[tuple[str, Path]]:
    seen: set[str] = set()
    for child in sorted(screenshots_dir.iterdir(), key=lambda item: item.name):
        if not child.is_dir():
            continue
        image_type = SCREENSHOT_DIR_TO_IMAGE_TYPE.get(child.name.lower())
        if image_type is None or image_type in seen:
            continue
        seen.add(image_type)
        yield image_type, child


def upload_images_for_locale(
    publisher,
    edit_id: str,
    locale: str,
    screenshots_dir: Path,
) -> None:
    for image_type, image_dir in iter_screenshot_directories(screenshots_dir):
        image_files = sorted(
            [path for path in image_dir.iterdir() if path.is_file() and path.suffix.lower() in ALLOWED_IMAGE_EXTENSIONS]
        )
        if not image_files:
            raise SystemExit(f"{image_dir} does not contain any PNG or JPG screenshots.")
        if len(image_files) > 8:
            raise SystemExit(f"{image_dir} contains more than 8 screenshots.")
        publisher.edits().images().deleteall(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            language=locale,
            imageType=image_type,
        ).execute()
        print(f"image_reset=language:{locale} type:{image_type} count:{len(image_files)}")
        for image_file in image_files:
            mime_type = mimetypes.guess_type(image_file.name)[0] or "image/png"
            media = MediaFileUpload(str(image_file), mimetype=mime_type, resumable=False)
            uploaded = publisher.edits().images().upload(
                packageName=PACKAGE_NAME,
                editId=edit_id,
                language=locale,
                imageType=image_type,
                media_body=media,
            ).execute()
            print(
                "image_upload="
                f"language:{locale} "
                f"type:{image_type} "
                f"id:{uploaded.get('id', '')} "
                f"url:{uploaded.get('url', '')}"
            )


def upload_feature_graphic(
    publisher,
    edit_id: str,
    locale: str,
    feature_graphic: Path,
) -> None:
    publisher.edits().images().deleteall(
        packageName=PACKAGE_NAME,
        editId=edit_id,
        language=locale,
        imageType="featureGraphic",
    ).execute()
    media = MediaFileUpload(
        str(feature_graphic),
        mimetype=mimetypes.guess_type(feature_graphic.name)[0] or "image/png",
        resumable=False,
    )
    uploaded = publisher.edits().images().upload(
        packageName=PACKAGE_NAME,
        editId=edit_id,
        language=locale,
        imageType="featureGraphic",
        media_body=media,
    ).execute()
    print(
        "feature_graphic_upload="
        f"language:{locale} "
        f"id:{uploaded.get('id', '')} "
        f"url:{uploaded.get('url', '')}"
    )


def update_screenshots(publisher, edit_id: str, screenshots_dir: Path) -> None:
    require_dir(screenshots_dir, "Screenshot directory")
    if not list(iter_screenshot_directories(screenshots_dir)):
        raise SystemExit(
            "No screenshot subdirectories were found. Expected folders such as phone, "
            "tablet_7in, or tablet_10in."
        )

    for locale in LISTING_LOCALES:
        upload_images_for_locale(publisher, edit_id, locale, screenshots_dir)


def track_payload_for_version(version_code: int) -> dict:
    return {
        "track": TRACK_NAME,
        "releases": [
            {
                "name": RELEASE_NAME,
                "versionCodes": [str(version_code)],
                "status": "completed",
                "releaseNotes": [
                    {"language": locale, "text": RELEASE_NOTES_BY_LOCALE[locale]}
                    for locale in LISTING_LOCALES
                ],
            }
        ],
    }


def inspect_mode(publisher) -> None:
    edit_id = create_edit(publisher)
    print(f"edit={edit_id}")
    try:
        describe_tracks(publisher, edit_id)
        describe_bundles(publisher, edit_id)
        describe_listings(publisher, edit_id)
        describe_details(publisher, edit_id)
        describe_images(publisher, edit_id)
    finally:
        delete_edit(publisher, edit_id)


def publish_mode(
    publisher,
    aab_path: Path,
    version_code: int,
    listing_dir: Path,
    feature_graphic: Path | None,
    screenshots_dir: Path,
) -> None:
    require_file(aab_path, "AAB")
    require_dir(listing_dir, "Listing directory")
    listing_payloads = load_listing_payloads(listing_dir)
    if feature_graphic is not None:
        validate_feature_graphic(feature_graphic)
    edit_id = create_edit(publisher)
    commit_succeeded = False
    commit_attempted = False
    commit_uncertain = False
    print(f"edit={edit_id}")
    print(f"aab_version_code={version_code}")

    try:
        ensure_version_is_unused(publisher, edit_id, version_code)
        bundle_media = MediaFileUpload(
            str(aab_path),
            mimetype="application/octet-stream",
            resumable=True,
            chunksize=8 * 1024 * 1024,
        )
        bundle = publisher.edits().bundles().upload(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            media_body=bundle_media,
        ).execute()
        uploaded_version = str(bundle.get("versionCode", ""))
        print(
            "bundle_upload="
            f"versionCode:{uploaded_version} "
            f"sha1:{bundle.get('sha1', '')} "
            f"sha256:{bundle.get('sha256', '')}"
        )
        if uploaded_version != str(version_code):
            raise SystemExit(
                f"Uploaded bundle version {uploaded_version} does not match expected version {version_code}."
            )
        expected_hash = hashlib.sha256(aab_path.read_bytes()).hexdigest()
        if bundle.get("sha256", "").lower() != expected_hash:
            raise SystemExit("Play's uploaded bundle SHA256 does not match the local signed AAB.")

        update_listings(publisher, edit_id, listing_payloads)
        if feature_graphic is not None:
            for locale in LISTING_LOCALES:
                upload_feature_graphic(publisher, edit_id, locale, feature_graphic)
        update_screenshots(publisher, edit_id, screenshots_dir)

        track = publisher.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track=TRACK_NAME,
            body=track_payload_for_version(version_code),
        ).execute()
        print_json_summary("track", track)

        publisher.edits().validate(packageName=PACKAGE_NAME, editId=edit_id).execute()
        print(f"validated=ok edit={edit_id}")
        commit_attempted = True
        publisher.edits().commit(packageName=PACKAGE_NAME, editId=edit_id).execute()
        commit_succeeded = True
        print(f"commit=ok edit={edit_id} track={TRACK_NAME} versionCode={version_code}")
    except Exception:
        commit_uncertain = commit_attempted and not commit_succeeded
        raise
    finally:
        if edit_id and not commit_succeeded and not commit_uncertain:
            delete_edit(publisher, edit_id)


def main() -> None:
    args = parse_args()
    credentials_path = Path(args.credentials).expanduser()
    require_file(credentials_path, "Credentials file")

    publisher = build_publisher(credentials_path)

    if args.command == "inspect":
        inspect_mode(publisher)
        return

    if args.command == "publish":
        publish_mode(
            publisher,
            aab_path=Path(args.aab).expanduser(),
            version_code=args.version_code,
            listing_dir=Path(args.listing_dir).expanduser(),
            feature_graphic=Path(args.feature_graphic).expanduser() if args.feature_graphic else None,
            screenshots_dir=Path(args.screenshots_dir).expanduser(),
        )
        return

    raise SystemExit(f"Unknown command: {args.command}")


if __name__ == "__main__":
    main()
