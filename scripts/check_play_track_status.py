"""Read Beta's Google Play track versions without publishing changes.

The Android Publisher API exposes tracks through a short-lived edit. This
script creates that edit, reads every standard track, and deletes the edit in
``finally`` without committing it.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build


SCOPE = "https://www.googleapis.com/auth/androidpublisher"
DEFAULT_PACKAGE = "live.betaapp.android"
TRACKS = ("internal", "alpha", "beta", "production")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--credentials",
        default=os.environ.get("BETA_PLAY_PUBLISHER_JSON", ""),
        help="Path to the Google Play Android Publisher service-account JSON.",
    )
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    credentials_path = Path(args.credentials).expanduser()
    if not credentials_path.is_file():
        raise SystemExit(
            "Provide --credentials or BETA_PLAY_PUBLISHER_JSON with a valid file."
        )

    credentials = service_account.Credentials.from_service_account_file(
        str(credentials_path),
        scopes=[SCOPE],
    )
    publisher = build("androidpublisher", "v3", credentials=credentials)
    edit = publisher.edits().insert(packageName=args.package, body={}).execute()
    edit_id = edit["id"]

    try:
        for track_name in TRACKS:
            track = publisher.edits().tracks().get(
                packageName=args.package,
                editId=edit_id,
                track=track_name,
            ).execute()
            releases = track.get("releases") or []
            print(f"track={track_name} releases={len(releases)}")
            for release in releases:
                version_codes = ",".join(release.get("versionCodes") or [])
                status = release.get("status", "unknown")
                fraction = release.get("userFraction")
                print(
                    f"  status={status} version_codes={version_codes} "
                    f"user_fraction={fraction}"
                )
    finally:
        publisher.edits().delete(
            packageName=args.package,
            editId=edit_id,
        ).execute()


if __name__ == "__main__":
    main()
