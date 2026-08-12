#!/usr/bin/env python3
"""Debug-only Swiggy MCP contract stub.

Safety:
- Synthetic responses only.
- No real user data.
- No checkout/clear routes are implemented.
- Mutation is permanently disabled.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import parse_qs, urlparse


DEFAULT_BACKEND_KEY = "stub-contract-key"
DEFAULT_ADDRESS_ID = "stub-address-1"
DEFAULT_ADDRESS_LABEL = "Codex Test Lane, Hyderabad, Telangana, 500081"
DEFAULT_CONFIRMATION_TOKEN = "stub-confirmation-token"
DEFAULT_PORT = 8787


@dataclass(frozen=True)
class StubConfig:
    host: str
    port: int
    backend_key: str
    log_file: Optional[Path]
    address_id: str
    address_label: str


class RequestStore:
    def __init__(self, log_file: Optional[Path]) -> None:
        self._log_file = log_file
        self._events: List[Dict[str, Any]] = []

    def record(self, event: Dict[str, Any]) -> None:
        self._events.append(event)
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        print(line, flush=True)
        if self._log_file is not None:
            self._log_file.parent.mkdir(parents=True, exist_ok=True)
            with self._log_file.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")

    @property
    def events(self) -> List[Dict[str, Any]]:
        return list(self._events)


def _json_response(handler: BaseHTTPRequestHandler, status: int, payload: Dict[str, Any]) -> None:
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    handler.wfile.write(data)


def _read_json_body(handler: BaseHTTPRequestHandler) -> Dict[str, Any]:
    length = int(handler.headers.get("Content-Length", "0") or "0")
    if length <= 0:
        return {}
    raw = handler.rfile.read(length)
    if not raw:
        return {}
    try:
        parsed = json.loads(raw.decode("utf-8"))
        return parsed if isinstance(parsed, dict) else {"_body": parsed}
    except json.JSONDecodeError:
        return {"_raw": raw.decode("utf-8", errors="replace")}


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _safe_query_list(query_value: Any) -> List[str]:
    if isinstance(query_value, list):
        return [str(item).strip() for item in query_value if str(item).strip()]
    if isinstance(query_value, str):
        return [query_value.strip()] if query_value.strip() else []
    return []


def _candidate_for_query(query: str, address_id: str, index: int) -> Dict[str, Any]:
    normalized = " ".join(query.split())
    spin_id = f"stub-spin-{index + 1}"
    return {
        "spinId": spin_id,
        "label": normalized,
        "variant": normalized,
        "subtitle": f"Synthetic suggestion for {address_id}",
        "suggested": True,
    }


def _recommendations_for_queries(queries: List[str], address_id: str) -> List[Dict[str, Any]]:
    results: List[Dict[str, Any]] = []
    for index, query in enumerate(queries):
        candidate = _candidate_for_query(query, address_id, index)
        results.append(
            {
                "query": query,
                "candidates": [candidate],
                "suggested": candidate,
                "requiresConfirmation": False,
            }
        )
    return results


def _cart_changes_from_items(items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    changes: List[Dict[str, Any]] = []
    for index, item in enumerate(items):
        display_name = str(item.get("displayName") or item.get("query") or f"Item {index + 1}").strip()
        quantity = item.get("quantity", 1)
        try:
            quantity_int = max(1, int(quantity))
        except (TypeError, ValueError):
            quantity_int = 1
        changes.append(
            {
                "spinId": str(item.get("spinId") or f"stub-spin-{index + 1}"),
                "kind": "add",
                "displayName": display_name,
                "fromQuantity": 0,
                "toQuantity": quantity_int,
                "description": f"Add {display_name} x{quantity_int}",
            }
        )
    return changes


class SwiggyStubHandler(BaseHTTPRequestHandler):
    server_version = "SwiggyMcpContractStub/1.0"

    @property
    def config(self) -> StubConfig:
        return self.server.config  # type: ignore[attr-defined]

    @property
    def store(self) -> RequestStore:
        return self.server.store  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A003 - matching BaseHTTPRequestHandler
        # Keep output deterministic via structured request logs instead.
        return

    def _require_backend_key(self) -> bool:
        supplied = self.headers.get("x-beta-backend-key", "").strip()
        return supplied == self.config.backend_key

    def _record(self, route: str, body: Optional[Dict[str, Any]] = None) -> None:
        self.store.record(
            {
                "ts": _now_iso(),
                "method": self.command,
                "path": self.path,
                "route": route,
                "body": body or {},
            }
        )

    def _reject(self, status: int, reason: str, message: str) -> None:
        _json_response(
            self,
            status,
            {
                "state": "ERROR",
                "reason": reason,
                "message": message,
                "timestamp": _now_iso(),
            },
        )

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        if path.startswith("/swiggy/") and not self._require_backend_key():
            self._record("backend-key-rejected", {"path": path, "query": query})
            self._reject(401, "backend_key_missing_or_invalid", "Debug stub rejected the backend key.")
            return
        if path == "/health":
            self._record("health")
            _json_response(
                self,
                200,
                {
                    "ok": True,
                    "service": "swiggy-mcp-contract-stub",
                    "mode": "debug-only",
                    "timestamp": _now_iso(),
                },
            )
            return
        if path == "/swiggy/status":
            self._record("status")
            _json_response(
                self,
                200,
                {
                    "state": "READY",
                    "ready": True,
                    "message": "Swiggy stub is READY.",
                    "reconnectRequired": False,
                },
            )
            return
        if path == "/swiggy/capabilities":
            self._record("capabilities")
            _json_response(
                self,
                200,
                {
                    "supported": [
                        "status",
                        "addresses",
                        "recommendations",
                        "cart/plan",
                    ],
                    "message": "Read-only Swiggy contract stub.",
                    "reconnectRequired": False,
                },
            )
            return
        if path == "/swiggy/connect":
            self._record("connect")
            _json_response(
                self,
                200,
                {
                    "message": "Swiggy stub is already connected.",
                    "authorizationUrl": None,
                    "reconnectRequired": False,
                    "state": "READY",
                    "ready": True,
                },
            )
            return
        if path == "/swiggy/addresses":
            self._record("addresses")
            _json_response(
                self,
                200,
                {
                    "addresses": [
                        {
                            "id": self.config.address_id,
                            "label": self.config.address_label,
                            "normalizedLabel": self.config.address_label,
                        }
                    ]
                },
            )
            return
        if path == "/swiggy/recommendations":
            self._record("recommendations", {"query": query})
            address_id = query.get("addressId", [self.config.address_id])[0] or self.config.address_id
            item_query = query.get("query", [""])[0]
            candidate = _candidate_for_query(item_query, address_id, 0)
            _json_response(
                self,
                200,
                {
                    "query": item_query,
                    "candidates": [candidate],
                    "suggested": candidate,
                    "requiresConfirmation": False,
                },
            )
            return

        self._record("unknown", {"path": path})
        _json_response(
            self,
            404,
            {
                "state": "NOT_FOUND",
                "reason": "route_absent",
                "message": "Route is absent in the debug-only stub.",
            },
        )

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        parsed = urlparse(self.path)
        path = parsed.path
        body = _read_json_body(self)

        if not self._require_backend_key():
            self._record("backend-key-rejected", body)
            self._reject(401, "backend_key_missing_or_invalid", "Debug stub rejected the backend key.")
            return

        if path == "/swiggy/connect":
            self._record("connect", body)
            _json_response(
                self,
                200,
                {
                    "message": "Swiggy stub is already connected.",
                    "authorizationUrl": None,
                    "reconnectRequired": False,
                    "state": "READY",
                    "ready": True,
                },
            )
            return
        if path == "/swiggy/disconnect":
            self._record("disconnect", body)
            _json_response(
                self,
                200,
                {
                    "state": "DISCONNECTED",
                    "ready": False,
                    "message": "Swiggy stub disconnected.",
                    "reconnectRequired": False,
                },
            )
            return
        if path == "/swiggy/recommendations/batch":
            self._record("recommendations-batch", body)
            address_id = str(body.get("addressId") or self.config.address_id).strip() or self.config.address_id
            queries = _safe_query_list(body.get("queries"))
            _json_response(
                self,
                200,
                {
                    "results": _recommendations_for_queries(queries, address_id),
                },
            )
            return
        if path == "/swiggy/cart/plan":
            self._record("cart-plan", body)
            requested_items = body.get("requestedItems")
            if not isinstance(requested_items, list):
                requested_items = []
            changes = _cart_changes_from_items(requested_items)
            _json_response(
                self,
                200,
                {
                    "changes": changes,
                    "confirmationToken": DEFAULT_CONFIRMATION_TOKEN,
                    "cartMutationEnabled": False,
                    "message": "Cart review prepared in debug-only stub mode.",
                },
            )
            return
        if path == "/swiggy/cart/apply":
            self._record("cart-apply-blocked", body)
            _json_response(
                self,
                409,
                {
                    "verified": False,
                    "message": "Cart mutation disabled in debug-only stub mode.",
                    "reason": "cart_mutation_disabled",
                    "reconnectRequired": False,
                    "mutationEnabled": False,
                },
            )
            return

        self._record("unknown", {"path": path, "body": body})
        _json_response(
            self,
            404,
            {
                "state": "NOT_FOUND",
                "reason": "route_absent",
                "message": "Route is absent in the debug-only stub.",
            },
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the debug-only Swiggy MCP contract stub.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--backend-key", default=DEFAULT_BACKEND_KEY)
    parser.add_argument("--log-file", default="")
    parser.add_argument("--address-id", default=DEFAULT_ADDRESS_ID)
    parser.add_argument("--address-label", default=DEFAULT_ADDRESS_LABEL)
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    log_file = Path(args.log_file).expanduser().resolve() if args.log_file else None
    config = StubConfig(
        host=args.host,
        port=args.port,
        backend_key=args.backend_key,
        log_file=log_file,
        address_id=args.address_id,
        address_label=args.address_label,
    )

    server = ThreadingHTTPServer((config.host, config.port), SwiggyStubHandler)
    server.config = config  # type: ignore[attr-defined]
    server.store = RequestStore(config.log_file)  # type: ignore[attr-defined]

    print(
        json.dumps(
            {
                "service": "swiggy-mcp-contract-stub",
                "mode": "debug-only",
                "host": config.host,
                "port": config.port,
                "backendKey": config.backend_key,
                "logFile": str(config.log_file) if config.log_file else None,
                "routes": [
                    "/health",
                    "/swiggy/status",
                    "/swiggy/capabilities",
                    "/swiggy/connect",
                    "/swiggy/disconnect",
                    "/swiggy/addresses",
                    "/swiggy/recommendations",
                    "/swiggy/recommendations/batch",
                    "/swiggy/cart/plan",
                    "/swiggy/cart/apply",
                ],
                "checkoutRoutes": [],
                "clearRoutes": [],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        flush=True,
    )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 130
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
