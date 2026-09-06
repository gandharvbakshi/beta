"""Update Beta's Google Play Data Safety CSV from an exported template.

Dry-run is the default. The script reads a current exported Data Safety CSV,
asserts that the exact machine-readable question/response IDs exist once, then
applies a conservative set of edits and prints a compact summary.

If ``--publish`` is supplied, the script posts the resulting CSV string to the
official Android Publisher Data Safety endpoint for the fixed package name
``live.betaapp.android``. Credentials are provided through a Google service
account JSON path and are never printed.
"""

from __future__ import annotations

import argparse
import csv
import io
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account


SCOPE = "https://www.googleapis.com/auth/androidpublisher"
DEFAULT_PACKAGE = "live.betaapp.android"
DEFAULT_PRIVACY_URL = "https://betaapp.live/privacy-policy.html"
DATA_SAFETY_ENDPOINT = (
    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{package}/dataSafety"
)


@dataclass(frozen=True)
class RowRef:
    question_id: str
    response_id: str | None = None


@dataclass(frozen=True)
class DataTypePlan:
    response_id: str
    question_id: str
    collection_purposes: tuple[str, ...]
    required: bool
    preserve_user_control: bool = False
    collect_mode: str = "PSL_DATA_USAGE_ONLY_COLLECTED"
    share_mode: str = "PSL_DATA_USAGE_ONLY_SHARED"
    ephemeral: str = "FALSE"


ACCOUNT_CREATION_METHODS = (
    "PSL_ACM_USER_ID_PASSWORD",
    "PSL_ACM_USER_ID_OTHER_AUTH",
    "PSL_ACM_USER_ID_PASSWORD_OTHER_AUTH",
    "PSL_ACM_OAUTH",
    "PSL_ACM_OTHER",
    "PSL_ACM_NONE",
)

OUTSIDE_APP_ACCOUNT_TYPES = (
    "PSL_LOGIN_WITH_OUTSIDE_APP_ID",
    "PSL_LOGIN_THROUGH_EMPLOYMENT_OR_ENTERPRISE_ACCOUNT",
    "PSL_OUTSIDE_APP_ACCOUNT_TYPE_OTHER",
)

DATA_TYPE_PLANS: tuple[DataTypePlan, ...] = (
    DataTypePlan(
        response_id="PSL_NAME",
        question_id="PSL_DATA_TYPES_PERSONAL",
        collection_purposes=("PSL_APP_FUNCTIONALITY", "PSL_ACCOUNT_MANAGEMENT"),
        required=True,
    ),
    DataTypePlan(
        response_id="PSL_PHONE",
        question_id="PSL_DATA_TYPES_PERSONAL",
        collection_purposes=("PSL_APP_FUNCTIONALITY", "PSL_ACCOUNT_MANAGEMENT"),
        required=True,
    ),
    DataTypePlan(
        response_id="PSL_USER_ACCOUNT",
        question_id="PSL_DATA_TYPES_PERSONAL",
        collection_purposes=("PSL_APP_FUNCTIONALITY", "PSL_ACCOUNT_MANAGEMENT"),
        required=True,
    ),
    DataTypePlan(
        response_id="PSL_PURCHASE_HISTORY",
        question_id="PSL_DATA_TYPES_FINANCIAL",
        collection_purposes=("PSL_APP_FUNCTIONALITY", "PSL_PERSONALIZATION"),
        required=True,
    ),
    DataTypePlan(
        response_id="PSL_APPROX_LOCATION",
        question_id="PSL_DATA_TYPES_LOCATION",
        collection_purposes=("PSL_APP_FUNCTIONALITY", "PSL_ANALYTICS", "PSL_ADVERTISING"),
        required=False,
        preserve_user_control=True,
    ),
    DataTypePlan(
        response_id="PSL_PRECISE_LOCATION",
        question_id="PSL_DATA_TYPES_LOCATION",
        collection_purposes=("PSL_APP_FUNCTIONALITY",),
        required=False,
    ),
    DataTypePlan(
        response_id="PSL_AUDIO",
        question_id="PSL_DATA_TYPES_AUDIO",
        collection_purposes=("PSL_APP_FUNCTIONALITY",),
        required=False,
    ),
    DataTypePlan(
        response_id="PSL_CRASH_LOGS",
        question_id="PSL_DATA_TYPES_APP_PERFORMANCE",
        collection_purposes=("PSL_ANALYTICS",),
        required=False,
    ),
    DataTypePlan(
        response_id="PSL_PERFORMANCE_DIAGNOSTICS",
        question_id="PSL_DATA_TYPES_APP_PERFORMANCE",
        collection_purposes=("PSL_ANALYTICS",),
        required=False,
    ),
    DataTypePlan(
        response_id="PSL_DEVICE_ID",
        question_id="PSL_DATA_TYPES_IDENTIFIERS",
        collection_purposes=("PSL_APP_FUNCTIONALITY", "PSL_ANALYTICS", "PSL_FRAUD_PREVENTION_SECURITY", "PSL_ADVERTISING"),
        required=True,
    ),
    DataTypePlan(
        response_id="PSL_USER_INTERACTION",
        question_id="PSL_DATA_TYPES_APP_ACTIVITY",
        collection_purposes=("PSL_APP_FUNCTIONALITY", "PSL_ANALYTICS", "PSL_DEVELOPER_COMMUNICATIONS", "PSL_ADVERTISING"),
        required=False,
        preserve_user_control=True,
    ),
)

TOP_LEVEL_REFS = (
    RowRef("PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA"),
    RowRef("PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT"),
    RowRef("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_OAUTH"),
    RowRef("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_NONE"),
    RowRef("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_USER_ID_PASSWORD"),
    RowRef("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_USER_ID_OTHER_AUTH"),
    RowRef("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_USER_ID_PASSWORD_OTHER_AUTH"),
    RowRef("PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_OTHER"),
    RowRef("PSL_ACCOUNT_DELETION_URL"),
    RowRef("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_YES"),
    RowRef("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_NO"),
    RowRef("PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_NO_AUTO_DELETED"),
    RowRef("PSL_DATA_DELETION_URL"),
    RowRef("PSL_HAS_OUTSIDE_APP_ACCOUNTS"),
    RowRef("PSL_OUTSIDE_APP_ACCOUNT_TYPES", "PSL_LOGIN_WITH_OUTSIDE_APP_ID"),
    RowRef("PSL_OUTSIDE_APP_ACCOUNT_TYPES", "PSL_LOGIN_THROUGH_EMPLOYMENT_OR_ENTERPRISE_ACCOUNT"),
    RowRef("PSL_OUTSIDE_APP_ACCOUNT_TYPES", "PSL_OUTSIDE_APP_ACCOUNT_TYPE_OTHER"),
)

FORBIDDEN_ROW_REFS = (
    RowRef("PSL_DATA_TYPES_FINANCIAL", "PSL_CREDIT_DEBIT_BANK_ACCOUNT_NUMBER"),
    RowRef("PSL_DATA_TYPES_FINANCIAL", "PSL_CREDIT_SCORE"),
    RowRef("PSL_DATA_TYPES_FINANCIAL", "PSL_OTHER"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_csv", help="Exported Google Play Data Safety CSV or '-' for stdin.")
    parser.add_argument(
        "--package",
        default=DEFAULT_PACKAGE,
        help="Google Play package name. Only live.betaapp.android is accepted.",
    )
    parser.add_argument(
        "--credentials",
        default=os.environ.get("BETA_PLAY_PUBLISHER_JSON", ""),
        help="Service-account JSON path used only when --publish is supplied.",
    )
    parser.add_argument(
        "--output-csv",
        default="",
        help="Optional output path for the transformed CSV. Dry-run remains the default.",
    )
    parser.add_argument(
        "--publish",
        action="store_true",
        help="POST the transformed CSV to applications.dataSafety after validation.",
    )
    parser.add_argument(
        "--include-feedback-email",
        action="store_true",
        help="Also disclose Email address for developer communications if a feedback email exists.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.package != DEFAULT_PACKAGE:
        raise SystemExit(f"--package must be exactly {DEFAULT_PACKAGE}")

    rows, fieldnames = read_csv(args.input_csv)
    index = build_index(rows)

    required_refs = list(TOP_LEVEL_REFS)
    required_refs.extend(RowRef(plan.question_id, plan.response_id) for plan in DATA_TYPE_PLANS)
    required_refs.extend(
        RowRef(
            f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:PSL_DATA_USAGE_COLLECTION_AND_SHARING",
            "PSL_DATA_USAGE_ONLY_COLLECTED",
        )
        for plan in DATA_TYPE_PLANS
    )
    required_refs.extend(
        RowRef(
            f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:PSL_DATA_USAGE_COLLECTION_AND_SHARING",
            "PSL_DATA_USAGE_ONLY_SHARED",
        )
        for plan in DATA_TYPE_PLANS
    )
    required_refs.extend(
        RowRef(f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:PSL_DATA_USAGE_EPHEMERAL") for plan in DATA_TYPE_PLANS
    )
    required_refs.extend(
        RowRef(f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:DATA_USAGE_USER_CONTROL", choice)
        for plan in DATA_TYPE_PLANS
        for choice in ("PSL_DATA_USAGE_USER_CONTROL_OPTIONAL", "PSL_DATA_USAGE_USER_CONTROL_REQUIRED")
    )
    required_refs.extend(
        RowRef(f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:DATA_USAGE_COLLECTION_PURPOSE", purpose)
        for plan in DATA_TYPE_PLANS
        for purpose in plan.collection_purposes
    )
    if args.include_feedback_email:
        required_refs.extend(
            [
                RowRef("PSL_DATA_TYPES_PERSONAL", "PSL_EMAIL"),
                RowRef("PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_COLLECTED"),
                RowRef("PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_SHARED"),
                RowRef("PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:PSL_DATA_USAGE_EPHEMERAL"),
                RowRef("PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:DATA_USAGE_USER_CONTROL", "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL"),
                RowRef("PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:DATA_USAGE_USER_CONTROL", "PSL_DATA_USAGE_USER_CONTROL_REQUIRED"),
                RowRef("PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:DATA_USAGE_COLLECTION_PURPOSE", "PSL_DEVELOPER_COMMUNICATIONS"),
            ]
        )

    ensure_exactly_once(rows, required_refs)
    ensure_exactly_once(rows, FORBIDDEN_ROW_REFS)

    set_true(rows, index, "PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA", None)
    set_true(rows, index, "PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT", None)
    set_true(rows, index, "PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_OAUTH")
    set_false(rows, index, "PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", "PSL_ACM_NONE")
    for response_id in ("PSL_ACM_USER_ID_PASSWORD", "PSL_ACM_USER_ID_OTHER_AUTH", "PSL_ACM_USER_ID_PASSWORD_OTHER_AUTH", "PSL_ACM_OTHER"):
        set_false(rows, index, "PSL_SUPPORTED_ACCOUNT_CREATION_METHODS", response_id)

    set_true(rows, index, "PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_YES")
    set_false(rows, index, "PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_NO")
    set_false(rows, index, "PSL_SUPPORT_DATA_DELETION_BY_USER", "DATA_DELETION_NO_AUTO_DELETED")
    set_value(rows, index, "PSL_DATA_DELETION_URL", None, DEFAULT_PRIVACY_URL)
    set_value(rows, index, "PSL_ACCOUNT_DELETION_URL", None, DEFAULT_PRIVACY_URL)

    # Play only accepts this alternate branch when account creation is NONE.
    # Swiggy OAuth is already declared above; clear inactive template answers.
    set_value(rows, index, "PSL_HAS_OUTSIDE_APP_ACCOUNTS", None, "")
    for response_id in OUTSIDE_APP_ACCOUNT_TYPES:
        set_value(rows, index, "PSL_OUTSIDE_APP_ACCOUNT_TYPES", response_id, "")

    selected_data_types: list[str] = []
    selected_purposes: list[str] = []
    selected_purpose_map: list[str] = []

    for plan in DATA_TYPE_PLANS:
        set_true(rows, index, plan.question_id, plan.response_id)
        selected_data_types.append(plan.response_id)
        set_true(rows, index, f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_COLLECTED")
        set_false(rows, index, f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_SHARED")
        set_false(rows, index, f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:PSL_DATA_USAGE_EPHEMERAL", None)
        if not plan.preserve_user_control:
            choose_user_control(rows, index, plan.response_id, plan.required)
        else:
            preserve_user_control(rows, index, plan.response_id)
        for purpose in plan.collection_purposes:
            set_true(rows, index, f"PSL_DATA_USAGE_RESPONSES:{plan.response_id}:DATA_USAGE_COLLECTION_PURPOSE", purpose)
            selected_purposes.append(purpose)
        selected_purpose_map.append(f"{plan.response_id}:{','.join(plan.collection_purposes)}")

    if args.include_feedback_email:
        set_true(rows, index, "PSL_DATA_TYPES_PERSONAL", "PSL_EMAIL")
        set_true(rows, index, "PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_COLLECTED")
        set_false(rows, index, "PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_SHARED")
        set_false(rows, index, "PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:PSL_DATA_USAGE_EPHEMERAL", None)
        choose_user_control(rows, index, "PSL_EMAIL", False)
        set_true(rows, index, "PSL_DATA_USAGE_RESPONSES:PSL_EMAIL:DATA_USAGE_COLLECTION_PURPOSE", "PSL_DEVELOPER_COMMUNICATIONS")
        selected_data_types.append("PSL_EMAIL")
        selected_purposes.append("PSL_DEVELOPER_COMMUNICATIONS")
        selected_purpose_map.append("PSL_EMAIL:PSL_DEVELOPER_COMMUNICATIONS")

    for ref in FORBIDDEN_ROW_REFS:
        set_false(rows, index, ref.question_id, ref.response_id)

    summary = build_summary(rows, args.package, selected_data_types, selected_purposes, selected_purpose_map, args.include_feedback_email)
    print(summary)

    output_csv = write_csv(rows, fieldnames)
    if args.output_csv:
        Path(args.output_csv).write_text(output_csv, encoding="utf-8")

    if args.publish:
        publish(args.package, output_csv, args.credentials)


def read_csv(path: str) -> tuple[list[dict[str, str]], list[str]]:
    if path == "-":
        import sys

        reader = csv.DictReader(io.StringIO(sys.stdin.read()))
        rows = list(reader)
        return rows, list(reader.fieldnames or [])

    with open(path, "r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)
        return rows, list(reader.fieldnames or [])


def write_csv(rows: list[dict[str, str]], fieldnames: list[str]) -> str:
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=fieldnames, lineterminator="\n")
    writer.writeheader()
    for row in rows:
        writer.writerow(row)
    return buffer.getvalue()


def canonical(value: str | None) -> str:
    return (value or "").strip()


def normalize_key(question_id: str | None, response_id: str | None) -> tuple[str, str]:
    return canonical(question_id), canonical(response_id)


def build_index(rows: list[dict[str, str]]) -> dict[tuple[str, str], int]:
    index: dict[tuple[str, str], int] = {}
    for idx, row in enumerate(rows):
        key = normalize_key(row.get("Question ID (machine readable)"), row.get("Response ID (machine readable)"))
        if key in index:
            raise SystemExit(f"Duplicate CSV row for {key[0]} / {key[1]}")
        index[key] = idx
    return index


def ensure_exactly_once(rows: list[dict[str, str]], refs: Iterable[RowRef]) -> None:
    index = build_index(rows)
    for ref in refs:
        key = normalize_key(ref.question_id, ref.response_id)
        if key not in index:
            raise SystemExit(f"Missing required Data Safety row: {ref.question_id} / {ref.response_id or '<question>'}")


def set_value(
    rows: list[dict[str, str]],
    index: dict[tuple[str, str], int],
    question_id: str,
    response_id: str | None,
    response_value: str,
) -> None:
    key = normalize_key(question_id, response_id)
    row_index = index.get(key)
    if row_index is None:
        raise SystemExit(f"Missing required Data Safety row: {question_id} / {response_id or '<question>'}")
    rows[row_index]["Response value"] = response_value


def set_true(rows: list[dict[str, str]], index: dict[tuple[str, str], int], question_id: str, response_id: str | None) -> None:
    set_value(rows, index, question_id, response_id, "true")


def set_false(rows: list[dict[str, str]], index: dict[tuple[str, str], int], question_id: str, response_id: str | None) -> None:
    set_value(rows, index, question_id, response_id, "false")


def choose_user_control(
    rows: list[dict[str, str]],
    index: dict[tuple[str, str], int],
    response_id: str,
    required: bool,
) -> None:
    set_true(
        rows,
        index,
        f"PSL_DATA_USAGE_RESPONSES:{response_id}:DATA_USAGE_USER_CONTROL",
        "PSL_DATA_USAGE_USER_CONTROL_REQUIRED" if required else "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL",
    )
    set_false(
        rows,
        index,
        f"PSL_DATA_USAGE_RESPONSES:{response_id}:DATA_USAGE_USER_CONTROL",
        "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL" if required else "PSL_DATA_USAGE_USER_CONTROL_REQUIRED",
    )


def preserve_user_control(
    rows: list[dict[str, str]],
    index: dict[tuple[str, str], int],
    response_id: str,
) -> None:
    optional_key = normalize_key(
        f"PSL_DATA_USAGE_RESPONSES:{response_id}:DATA_USAGE_USER_CONTROL",
        "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL",
    )
    required_key = normalize_key(
        f"PSL_DATA_USAGE_RESPONSES:{response_id}:DATA_USAGE_USER_CONTROL",
        "PSL_DATA_USAGE_USER_CONTROL_REQUIRED",
    )
    optional_row = rows[index[optional_key]]
    required_row = rows[index[required_key]]
    if canonical(optional_row.get("Response value")) == "true":
        return
    if canonical(required_row.get("Response value")) == "true":
        return
    set_true(rows, index, f"PSL_DATA_USAGE_RESPONSES:{response_id}:DATA_USAGE_USER_CONTROL", "PSL_DATA_USAGE_USER_CONTROL_REQUIRED")


def build_summary(
    rows: list[dict[str, str]],
    package: str,
    selected_data_types: list[str],
    selected_purposes: list[str],
    selected_purpose_map: list[str],
    include_email: bool,
) -> str:
    true_rows = {
        f'{row["Question ID (machine readable)"]}:{row["Response ID (machine readable)"]}'.rstrip(":")
        for row in rows
        if canonical(row.get("Response value")) == "true"
    }
    selected_type_summary = ", ".join(selected_data_types)
    purpose_summary = ", ".join(sorted(set(selected_purposes)))
    purpose_map_summary = "; ".join(selected_purpose_map)
    true_data_type_rows = ", ".join(
        sorted(
            row
            for row in true_rows
            if row.split(":", 1)[0]
            in {
                "PSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA",
                "PSL_DATA_COLLECTION_ENCRYPTED_IN_TRANSIT",
                "PSL_SUPPORTED_ACCOUNT_CREATION_METHODS",
                "PSL_SUPPORT_DATA_DELETION_BY_USER",
                "PSL_HAS_OUTSIDE_APP_ACCOUNTS",
                "PSL_DATA_TYPES_PERSONAL",
                "PSL_DATA_TYPES_FINANCIAL",
                "PSL_DATA_TYPES_LOCATION",
                "PSL_DATA_TYPES_AUDIO",
                "PSL_DATA_TYPES_APP_PERFORMANCE",
                "PSL_DATA_TYPES_APP_ACTIVITY",
                "PSL_DATA_TYPES_IDENTIFIERS",
            }
        )
    )
    lines = [
        f"package={package}",
        f"privacy_policy_url={DEFAULT_PRIVACY_URL}",
        f"data_deletion_url={DEFAULT_PRIVACY_URL}",
        f"account_deletion_url={DEFAULT_PRIVACY_URL}",
        "account_creation_methods=oauth=true, none=false",
        "outside_app_accounts=not_applicable_with_oauth_creation",
        f"selected_data_types={selected_type_summary}",
        f"selected_purposes={purpose_summary}",
        f"purpose_map={purpose_map_summary}",
        f"true_data_type_rows={true_data_type_rows}",
        "forbidden_financial_rows=credit_debit_bank_account_number=false, credit_score=false, other=false",
        f"feedback_email={'included' if include_email else 'omitted'}",
    ]
    return "\n".join(lines)


def publish(package: str, safety_labels_csv: str, credentials_path: str) -> None:
    credentials_file = Path(credentials_path).expanduser()
    if not credentials_file.is_file():
        raise SystemExit("Provide --credentials or BETA_PLAY_PUBLISHER_JSON with a valid file before --publish.")

    credentials = service_account.Credentials.from_service_account_file(
        str(credentials_file),
        scopes=[SCOPE],
    )
    authed_session = AuthorizedSession(credentials)
    response = authed_session.post(
        DATA_SAFETY_ENDPOINT.format(package=package),
        json={"safetyLabels": safety_labels_csv},
        timeout=30,
    )
    if not response.ok:
        # Policy-validation diagnostics contain no credentials or user payloads.
        raise SystemExit(f"Data Safety update rejected ({response.status_code}): {response.text[:3000]}")
    print("publish=ok")


if __name__ == "__main__":
    main()
