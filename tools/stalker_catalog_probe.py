#!/usr/bin/env python3
"""Validate Stalker wildcard catalog coverage and measure crawl cost.

Compares a category-less (wildcard) whole-catalog walk against the
per-category walk on the same portal session, mirroring the app's page-base
inference (`StalkerProvider.inferVodPageBase`) and reporting the extra stock
Ministra behavior the app cannot currently see (`p=0` = last page).

The tool never prints credentials or full provider URLs; the report contains
the portal host, row counts, request counts, and timing only.

Run against the local fixture:

    python tools/stalker_catalog_fixture.py --port 8767 &
    python tools/stalker_catalog_probe.py --portal-url http://127.0.0.1:8767 --fixture-mode

Run against a real portal (paced, bounded):

    python tools/stalker_catalog_probe.py --portal-url https://portal.example.com/c \\
        --mac 00:1A:79:12:34:56 --max-pages 50 --max-categories 20
"""
from __future__ import annotations

import argparse
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

USER_AGENT = ("Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) "
              "MAG200 stbapp ver: 4 rev: 1812 Mobile Safari/533.3")
X_USER_AGENT = "Model: MAG254; Link: Ethernet"


@dataclass
class ProbeOptions:
    max_pages: int = 5_000
    max_categories: int = 10_000
    sortby: str = ""
    request_interval_ms: int = 0
    timeout_seconds: float = 30.0
    probe_category: int = 1


@dataclass
class Counters:
    requests: int = 0
    ordered_list_requests: int = 0
    retries: int = 0
    throttled: int = 0
    server_errors: int = 0


class PortalError(RuntimeError):
    pass


@dataclass
class Page:
    total_items: int
    max_page_items: int
    rows: list[dict] = field(default_factory=list)


class StalkerClient:
    def __init__(self, portal_url: str, mac: str, options: ProbeOptions,
                 username: str = "", password: str = ""):
        self.portal_url = portal_url.rstrip("/")
        self.mac = mac
        self.username = username
        self.password = password
        self.options = options
        self.counters = Counters()
        self.token = ""
        self.load_url = self.portal_url + "/server/load.php"
        self.last_request_at = 0.0

    def _paced(self):
        interval = self.options.request_interval_ms / 1000
        if interval <= 0:
            return
        wait = self.last_request_at + interval - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        self.last_request_at = time.monotonic()

    def _request(self, query: dict[str, Any], attempts: int = 3) -> dict:
        url = self.load_url + "?" + urllib.parse.urlencode(query)
        request = urllib.request.Request(url, headers={
            "User-Agent": USER_AGENT,
            "X-User-Agent": X_USER_AGENT,
            "Referer": self.portal_url + "/c/",
            "Accept": "*/*",
            "Cookie": "mac=" + self.mac + "; stb_lang=en; timezone=UTC",
            **({"Authorization": "Bearer " + self.token} if self.token else {}),
        })
        last_error: Exception | None = None
        for attempt in range(attempts):
            self._paced()
            self.counters.requests += 1
            try:
                with urllib.request.urlopen(request, timeout=self.options.timeout_seconds) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code == 429:
                    self.counters.throttled += 1
                elif error.code >= 500:
                    self.counters.server_errors += 1
                retry_after = error.headers.get("Retry-After") if error.headers else None
                last_error = error
                error.close()
                if error.code not in (429, 500, 502, 503, 504) or attempt == attempts - 1:
                    raise PortalError(f"HTTP {error.code}") from error
                self.counters.retries += 1
                delay = min(float(retry_after), 5.0) if retry_after and retry_after.replace(".", "", 1).isdigit() else 1.0
                time.sleep(delay)
            except (urllib.error.URLError, TimeoutError) as error:
                last_error = error
                if attempt == attempts - 1:
                    raise PortalError(f"transport failure: {type(error).__name__}") from error
                self.counters.retries += 1
                time.sleep(1.0)
        raise PortalError("request failed") from last_error

    def authenticate(self):
        payload = self._request({"type": "stb", "action": "handshake", "token": "", "JsHttpRequest": "1-xml"})
        self.token = str((payload.get("js") or {}).get("token") or "")
        if not self.token:
            raise PortalError("handshake did not return a token")
        self._request({"type": "stb", "action": "get_profile", "JsHttpRequest": "1-xml"})
        if self.username:
            self._request({
                "type": "stb", "action": "do_auth",
                "login": self.username, "password": self.password,
                "JsHttpRequest": "1-xml",
            })

    def categories(self) -> list[dict]:
        payload = self._request({"type": "vod", "action": "get_categories", "JsHttpRequest": "1-xml"})
        categories = payload.get("js") or []
        if isinstance(categories, dict):
            categories = categories.get("data") or []
        return [category for category in categories if isinstance(category, dict)]

    def ordered_list(self, category_id: str | None, page: int, sortby: str = "") -> Page:
        self.counters.ordered_list_requests += 1
        query: dict[str, Any] = {
            "type": "vod", "action": "get_ordered_list", "p": str(page), "JsHttpRequest": "1-xml",
        }
        if category_id is not None:
            query["category"] = category_id
        if sortby:
            query["sortby"] = sortby
        payload = self._request(query)
        js = payload.get("js") or {}
        if not isinstance(js, dict):
            raise PortalError("ordered list response is not an object")
        rows = js.get("data") or []
        return Page(
            total_items=_int_or_zero(js.get("total_items")),
            max_page_items=_int_or_zero(js.get("max_page_items")),
            rows=[row for row in rows if isinstance(row, dict)],
        )


def _int_or_zero(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _page_signature(rows: list[dict]) -> list[str]:
    return [str(row.get("id")) for row in rows]


def detect_page_base(client: StalkerClient, category_id: str, options: ProbeOptions) -> dict:
    """Mirrors StalkerProvider.inferVodPageBase and adds stock p=0 detection."""
    page_zero = client.ordered_list(category_id, 0, options.sortby)
    page_one = client.ordered_list(category_id, 1, options.sortby)
    zero_ids = _page_signature(page_zero.rows)
    one_ids = _page_signature(page_one.rows)
    pages = math.ceil(page_zero.total_items / page_zero.max_page_items) if page_zero.max_page_items else 1
    stock_last_page = False
    if zero_ids and one_ids and zero_ids != one_ids and pages > 1:
        last = client.ordered_list(category_id, pages, options.sortby)
        stock_last_page = _page_signature(last.rows) == zero_ids
    if not zero_ids and not one_ids:
        base, kind = 1, "UNKNOWN"
    elif zero_ids and one_ids and zero_ids == one_ids:
        base, kind = 1, "ONE_BASED_CLAMPED"
    elif zero_ids and not one_ids:
        base, kind = 0, "ZERO_BASED"
    elif zero_ids and one_ids and stock_last_page:
        base, kind = 0, "STOCK_LAST_PAGE_ON_ZERO"
    elif zero_ids:
        base, kind = 0, "ZERO_BASED"
    else:
        base, kind = 1, "ONE_BASED"
    return {
        "page_base": base,
        "page_base_kind": kind,
        "stock_page_zero_is_last_page": stock_last_page,
        "p0_rows": len(zero_ids),
        "p1_rows": len(one_ids),
        "p0_total_items": page_zero.total_items,
        "p1_total_items": page_one.total_items,
        "app_inference": base,
    }


@dataclass
class CrawlResult:
    scope: str
    rows: dict[str, dict] = field(default_factory=dict)
    duplicates: int = 0
    pages_fetched: int = 0
    early_empty_page: int | None = None
    totals: set[int] = field(default_factory=set)
    max_page_items: int = 0


def crawl_scope(client: StalkerClient, category_id: str | None, scope: str, base: int,
                options: ProbeOptions, budget: list[int]) -> CrawlResult:
    result = CrawlResult(scope=scope)
    page = 1
    while page <= options.max_pages and budget[0] > 0:
        portal_page = page - 1 + base
        response = client.ordered_list(category_id, portal_page, options.sortby)
        budget[0] -= 1
        result.pages_fetched += 1
        result.totals.add(response.total_items)
        result.max_page_items = result.max_page_items or response.max_page_items
        for row in response.rows:
            row_id = str(row.get("id"))
            if row_id in result.rows:
                result.duplicates += 1
            result.rows[row_id] = row
        pages = math.ceil(response.total_items / response.max_page_items) if response.max_page_items else 0
        if not response.rows and pages and page < pages:
            result.early_empty_page = page
            break
        if not response.rows or page >= pages:
            break
        page += 1
    return result


def probe(client: StalkerClient, options: ProbeOptions) -> dict:
    started = time.monotonic()
    client.authenticate()
    categories = [category for category in client.categories() if str(category.get("id")) != "*"]
    if options.max_categories:
        categories = categories[:options.max_categories]
    if not categories:
        raise PortalError("portal returned no VOD categories")
    detection_category = str(categories[min(options.probe_category - 1, len(categories) - 1)].get("id"))
    base_info = detect_page_base(client, detection_category, options)
    base = base_info["page_base"]

    budget = [options.max_pages]
    category_results = [crawl_scope(client, str(category.get("id")), str(category.get("id")),
                                    base, options, budget) for category in categories]
    wildcard_budget = [options.max_pages]
    wildcard = crawl_scope(client, None, "*", base, options, wildcard_budget)

    per_category_ids: dict[str, dict] = {}
    category_names = {str(category.get("id")): str(category.get("title") or "") for category in categories}
    for result in category_results:
        per_category_ids.update(result.rows)
    missing = {row_id: row for row_id, row in per_category_ids.items() if row_id not in wildcard.rows}
    missing_by_category: dict[str, int] = {}
    for row in missing.values():
        key = str(row.get("category_id"))
        missing_by_category[key] = missing_by_category.get(key, 0) + 1
    extra = {row_id: row for row_id, row in wildcard.rows.items() if row_id not in per_category_ids}
    unexpected_categories = sorted({
        str(row.get("category_id")) for row in wildcard.rows.values()
        if str(row.get("category_id")) not in category_names
    })
    wildcard_by_category: dict[str, int] = {}
    for row in wildcard.rows.values():
        key = str(row.get("category_id"))
        wildcard_by_category[key] = wildcard_by_category.get(key, 0) + 1
    excluded_categories = sorted(
        category_id for category_id in missing_by_category
        if wildcard_by_category.get(category_id, 0) == 0
    )
    missing_outside_excluded = {
        category_id: count for category_id, count in missing_by_category.items()
        if category_id not in excluded_categories
    }
    totals_stable = all(len(result.totals) <= 1 for result in category_results) and len(wildcard.totals) <= 1
    elapsed_ms = round((time.monotonic() - started) * 1000, 3)
    per_category_requests = sum(result.pages_fetched for result in category_results)

    if not wildcard.rows:
        coverage = "wildcard_unsupported"
    elif missing and not missing_outside_excluded:
        coverage = "complete_with_excluded_categories"
    elif missing:
        coverage = "incomplete"
    else:
        coverage = "complete"

    warnings: list[str] = []
    if base_info["stock_page_zero_is_last_page"]:
        warnings.append("p=0 returns the last page (stock Ministra); the app's inference maps it to a zero-based first page")
    if wildcard.duplicates:
        warnings.append(f"wildcard repeated {wildcard.duplicates} rows across pages")
    if any(result.duplicates for result in category_results):
        warnings.append("per-category crawl repeated rows across pages")
    if any(result.early_empty_page is not None for result in category_results) or wildcard.early_empty_page is not None:
        warnings.append("a page walk hit an empty page before the advertised total was reached")
    if not totals_stable:
        warnings.append("advertised total_items changed during the crawl")

    return {
        "portal_host": urllib.parse.urlparse(client.portal_url).netloc,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "detection_category": detection_category,
        **base_info,
        "categories_crawled": len(categories),
        "per_category": {
            "rows": len(per_category_ids),
            "requests": per_category_requests,
            "duplicates": sum(result.duplicates for result in category_results),
        },
        "wildcard": {
            "rows": len(wildcard.rows),
            "requests": wildcard.pages_fetched,
            "duplicates": wildcard.duplicates,
        },
        "missing_rows": len(missing),
        "missing_by_category": missing_by_category,
        "excluded_categories": excluded_categories,
        "extra_rows": len(extra),
        "unexpected_category_ids": unexpected_categories,
        "coverage": coverage,
        "totals_stable": totals_stable,
        "request_interval_ms": options.request_interval_ms,
        "counters": {
            "requests": client.counters.requests,
            "ordered_list_requests": client.counters.ordered_list_requests,
            "retries": client.counters.retries,
            "throttled": client.counters.throttled,
            "server_errors": client.counters.server_errors,
        },
        "elapsed_ms": elapsed_ms,
        "rows_per_second": round((len(per_category_ids) + len(wildcard.rows)) / (elapsed_ms / 1000), 2) if elapsed_ms else None,
        "warnings": warnings,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--portal-url", required=True)
    parser.add_argument("--mac", default="00:1A:79:12:34:56")
    parser.add_argument("--username", default="")
    parser.add_argument("--password", default="")
    parser.add_argument("--max-pages", type=int, default=5_000)
    parser.add_argument("--max-categories", type=int, default=10_000)
    parser.add_argument("--sortby", default="")
    parser.add_argument("--request-interval-ms", type=int, default=None,
                        help="Defaults to 0 for loopback portals and 1000 for remote portals")
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    loopback = urllib.parse.urlparse(args.portal_url).hostname in ("127.0.0.1", "localhost", "::1")
    interval = args.request_interval_ms if args.request_interval_ms is not None else (0 if loopback else 1_000)
    options = ProbeOptions(
        max_pages=args.max_pages,
        max_categories=args.max_categories,
        sortby=args.sortby,
        request_interval_ms=interval,
        timeout_seconds=args.timeout_seconds,
    )
    client = StalkerClient(args.portal_url, args.mac, options, args.username, args.password)
    report = probe(client, options)
    output = args.output or Path(__file__).resolve().parents[1] / "build" / "stalker-catalog-probe" / (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "portal_host", "page_base_kind", "coverage", "per_category", "wildcard",
        "missing_rows", "missing_by_category", "totals_stable", "elapsed_ms", "warnings",
    )}, indent=2))
    print(f"report: {output}")
    if report["coverage"] == "incomplete" or not report["totals_stable"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
