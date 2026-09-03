"""Semantic ADB journey for the Phase 5 Catalog extraction.

The harness deliberately talks to the production activity through UIAutomator
and D-pad/input events.  It does not seed data, edit local.properties, or
inspect provider credentials.  Start ``catalog_xtream_fixture.py`` and install
a debug APK configured for it before invoking this script.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


DEFAULT_PACKAGE = "com.streamvault.app.debug"
DEFAULT_ACTIVITY = "com.streamvault.app.MainActivity"
DEFAULT_TIMEOUT_SECONDS = 30.0
DEFAULT_ADB = Path(r"E:\androidSdk\platform-tools\adb.exe")
TOP_LEVEL_DESTINATIONS = {
    "home": 0,
    "live_tv": 1,
    "movies": 2,
    "series": 3,
    "downloads": 4,
    "epg": 5,
    "search": 6,
    "plugins": 7,
    "settings": 8,
}


class CatalogValidationError(RuntimeError):
    """Raised when a connected Catalog journey cannot prove its contract."""


@dataclass(frozen=True)
class UiNode:
    text: str
    content_description: str
    clickable: bool
    bounds: tuple[int, int, int, int] | None


def parse_bounds(value: str) -> tuple[int, int, int, int] | None:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", value.strip())
    if not match:
        return None
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def _parse_nodes(dump: str) -> list[UiNode]:
    try:
        root = ET.fromstring(dump)
    except ET.ParseError as exc:
        raise CatalogValidationError(f"UIAutomator returned invalid XML: {exc}") from exc

    nodes: list[UiNode] = []
    for element in root.iter("node"):
        nodes.append(
            UiNode(
                text=(element.attrib.get("text") or "").strip(),
                content_description=(element.attrib.get("content-desc") or "").strip(),
                clickable=element.attrib.get("clickable") == "true",
                bounds=parse_bounds(element.attrib.get("bounds") or ""),
            )
        )
    return nodes


def _tappable_targets(dump: str, predicate) -> list[UiNode]:
    """Find clickable ancestors for matching semantic nodes in a UI dump."""

    try:
        root = ET.fromstring(dump)
    except ET.ParseError as exc:
        raise CatalogValidationError(f"UIAutomator returned invalid XML: {exc}") from exc
    parents = {child: parent for parent in root.iter() for child in parent}
    targets: list[UiNode] = []
    for element in root.iter("node"):
        if not predicate(element):
            continue
        current = element
        while current is not None:
            if current.attrib.get("clickable") == "true":
                bounds = parse_bounds(current.attrib.get("bounds") or "")
                if bounds:
                    targets.append(
                        UiNode(
                            text=(current.attrib.get("text") or "").strip(),
                            content_description=(current.attrib.get("content-desc") or "").strip(),
                            clickable=True,
                            bounds=bounds,
                        )
                    )
                    break
            current = parents.get(current)
    return targets


def extract_ui_strings(dump: str) -> set[str]:
    """Return non-empty semantic text and content descriptions in a dump."""

    values: set[str] = set()
    for node in _parse_nodes(dump):
        values.update(value for value in (node.text, node.content_description) if value)
    return values


def assert_snapshot(
    dump: str,
    *,
    required: Iterable[str] = (),
    forbidden: Iterable[str] = (),
) -> None:
    """Assert semantic markers in a UIAutomator dump with actionable errors."""

    values = extract_ui_strings(dump)
    haystack = "\n".join(sorted(values))
    for marker in required:
        if marker not in values and marker not in haystack:
            raise AssertionError(f"missing marker '{marker}'")
    for marker in forbidden:
        if marker in values or marker in haystack:
            raise AssertionError(f"forbidden marker '{marker}'")


def _read_sdk_dir(root: Path) -> Path | None:
    properties = root / "local.properties"
    if not properties.exists():
        return None
    for line in properties.read_text(encoding="utf-8").splitlines():
        if not line.startswith("sdk.dir="):
            continue
        value = line[len("sdk.dir=") :].replace(r"\:", ":").replace(r"\\", "\\")
        return Path(value)
    return None


def resolve_adb(root: Path, requested: str | None) -> Path:
    candidates: list[Path] = []
    if requested:
        candidates.append(Path(requested))
    sdk_dir = _read_sdk_dir(root)
    if sdk_dir:
        candidates.append(sdk_dir / "platform-tools" / "adb.exe")
    candidates.append(DEFAULT_ADB)
    for candidate in candidates:
        if candidate.exists():
            return candidate
    rendered = ", ".join(str(candidate) for candidate in candidates)
    raise CatalogValidationError(f"ADB was not found. Checked: {rendered}")


class AdbClient:
    def __init__(self, adb: Path, serial: str | None, output_directory: Path):
        self.adb = adb
        self.serial = serial
        self.output_directory = output_directory
        self.output_directory.mkdir(parents=True, exist_ok=True)
        self._remote_dump = "/sdcard/streamvault_catalog_validation.xml"
        self._snapshot_index = 0

    def run(self, *arguments: str, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> str:
        command = [str(self.adb)]
        if self.serial:
            command.extend(["-s", self.serial])
        command.extend(arguments)
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if completed.returncode != 0:
            details = (completed.stderr or completed.stdout).strip()
            raise CatalogValidationError(
                f"ADB command failed ({completed.returncode}): {' '.join(command)}\n{details}"
            )
        return completed.stdout

    def shell(self, *arguments: str, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> str:
        return self.run("shell", *arguments, timeout=timeout)

    def key(self, keycode: str) -> None:
        self.shell("input", "keyevent", keycode)

    def check_device(self) -> None:
        output = self.run("devices")
        if self.serial:
            connected = re.search(rf"^{re.escape(self.serial)}\s+device(?:\s|$)", output, re.MULTILINE)
            if not connected:
                raise CatalogValidationError(f"ADB device '{self.serial}' is not connected.")
            return
        devices = re.findall(r"^(\S+)\s+device(?:\s|$)", output, re.MULTILINE)
        if not devices:
            raise CatalogValidationError("No attached Android device or emulator is ready.")
        self.serial = devices[0]

    def snapshot(self, label: str) -> str:
        self.shell("uiautomator", "dump", self._remote_dump)
        local_path = self.output_directory / f"{self._snapshot_index:03d}_{label}.xml"
        self._snapshot_index += 1
        self.run("pull", self._remote_dump, str(local_path))
        try:
            return local_path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            raise CatalogValidationError(f"Could not read UI dump {local_path}: {exc}") from exc

    def wait_for(
        self,
        label: str,
        *,
        required: Sequence[str] = (),
        forbidden: Sequence[str] = (),
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> str:
        deadline = time.monotonic() + timeout
        last_dump = ""
        while time.monotonic() < deadline:
            last_dump = self.snapshot(label)
            try:
                assert_snapshot(last_dump, required=required, forbidden=forbidden)
                return last_dump
            except AssertionError:
                time.sleep(0.5)
        missing = [marker for marker in required if marker not in extract_ui_strings(last_dump)]
        raise CatalogValidationError(
            f"Timed out waiting for {label}; missing markers: {', '.join(missing) or 'unknown'}. "
            f"Latest dump is in {self.output_directory}."
        )

    def wait_for_any(
        self,
        label: str,
        alternatives: Sequence[str],
        *,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> str:
        deadline = time.monotonic() + timeout
        last_dump = ""
        while time.monotonic() < deadline:
            last_dump = self.snapshot(label)
            values = extract_ui_strings(last_dump)
            if any(marker in values or marker in "\n".join(sorted(values)) for marker in alternatives):
                return last_dump
            time.sleep(0.5)
        raise CatalogValidationError(
            f"Timed out waiting for {label}; expected one of: {', '.join(alternatives)}. "
            f"Latest dump is in {self.output_directory}."
        )

    def tap_marker(self, marker: str, *, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            dump = self.snapshot(f"before_tap_{_safe_name(marker)}")
            candidates = _tappable_targets(
                dump,
                lambda element: marker in {
                    (element.attrib.get("text") or "").strip(),
                    (element.attrib.get("content-desc") or "").strip(),
                },
            )
            if candidates and candidates[0].bounds:
                left, top, right, bottom = candidates[0].bounds
                # TV cards can place a focus/preview overlay over their lower half.  A point
                # near the upper third remains inside the clickable surface without assuming
                # a fixed card size or screen density.
                tap_y = top + max(1, (bottom - top) // 3)
                self.shell("input", "tap", str((left + right) // 2), str(tap_y))
                return
            time.sleep(0.5)
        raise CatalogValidationError(f"Could not find tappable marker '{marker}'.")

    def focus_and_activate_marker(self, marker: str, *, timeout: float = DEFAULT_TIMEOUT_SECONDS) -> None:
        """Use the TV remote path to activate a card containing ``marker``.

        Compose TV cards expose the title as a non-clickable semantic child and
        handle activation through their focused parent.  A raw coordinate tap
        is therefore not reliable across TV input modes; this helper follows
        focus and activates only after the focused card contains the marker.
        """

        deadline = time.monotonic() + timeout
        directions = ["KEYCODE_DPAD_DOWN"] * 4 + [
            "KEYCODE_DPAD_RIGHT",
            "KEYCODE_DPAD_LEFT",
            "KEYCODE_DPAD_RIGHT",
            "KEYCODE_DPAD_LEFT",
        ]
        for direction in directions:
            if time.monotonic() >= deadline:
                break
            self.key(direction)
            dump = self.snapshot(f"focus_{_safe_name(marker)}")
            if _focused_target_contains(dump, marker):
                self.key("KEYCODE_DPAD_CENTER")
                return
        raise CatalogValidationError(f"Could not focus a card containing marker '{marker}'.")

    def focus_and_activate_matching(
        self,
        patterns: Sequence[str],
        *,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        max_steps: int = 8,
    ) -> str:
        """Activate a currently visible action by walking the focused row."""

        compiled = [re.compile(pattern, re.IGNORECASE) for pattern in patterns]
        deadline = time.monotonic() + timeout
        for _ in range(max_steps):
            if time.monotonic() >= deadline:
                break
            dump = self.snapshot("focus_action")
            if _focused_target_matches(dump, compiled):
                self.key("KEYCODE_DPAD_CENTER")
                return "activated"
            self.key("KEYCODE_DPAD_RIGHT")
        raise CatalogValidationError("Could not focus a matching detail action.")

    def navigate(self, route: str, package: str, activity: str) -> None:
        if route not in TOP_LEVEL_DESTINATIONS:
            raise CatalogValidationError(f"Unknown top-level route '{route}'.")
        if route == "home":
            self.shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-n", f"{package}/{activity}")
            return
        for _ in range(40):
            self.key("KEYCODE_DPAD_UP")
        for _ in range(10):
            self.key("KEYCODE_DPAD_LEFT")
        for _ in range(TOP_LEVEL_DESTINATIONS[route]):
            self.key("KEYCODE_DPAD_RIGHT")
        self.key("KEYCODE_DPAD_CENTER")


def _safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value).strip("_") or "marker"


def _focused_target_contains(dump: str, marker: str) -> bool:
    return _focused_target_matches(dump, [re.compile(re.escape(marker), re.IGNORECASE)])


def _focused_target_matches(dump: str, patterns: Sequence[re.Pattern[str]]) -> bool:
    try:
        root = ET.fromstring(dump)
    except ET.ParseError as exc:
        raise CatalogValidationError(f"UIAutomator returned invalid XML: {exc}") from exc
    parents = {child: parent for parent in root.iter() for child in parent}
    focused = next((node for node in root.iter("node") if node.attrib.get("focused") == "true"), None)
    if focused is None:
        return False
    target = focused
    while target is not None and target.attrib.get("clickable") != "true":
        target = parents.get(target)
    if target is None:
        return False
    return any(
        pattern.search(value)
        for node in target.iter("node")
        for value in (
            (node.attrib.get("text") or "").strip(),
            (node.attrib.get("content-desc") or "").strip(),
        )
        for pattern in patterns
    )


def run_journey(client: AdbClient, package: str, activity: str, timeout: float) -> list[str]:
    client.shell("am", "force-stop", package)
    client.shell("am", "start", "-W", "-n", f"{package}/{activity}")
    client.wait_for(
        "home",
        required=("streamvault.destination:home", "Fixture Movie One"),
        forbidden=("Sync needed",),
        timeout=timeout,
    )
    client.shell("input", "swipe", "960", "900", "960", "320", "500")
    client.wait_for("home_series_shelf", required=("streamvault.destination:home", "Fixture Series One"), timeout=timeout)

    client.navigate("movies", package, activity)
    client.wait_for(
        "movies_browse",
        required=("streamvault.destination:movies", "Fixture Movie One", "Fixture Movie Two"),
        timeout=timeout,
    )
    client.focus_and_activate_marker("Fixture Movie One")
    client.wait_for("movie_detail", required=("Fixture Movie One", "Play", "Copy URL", "Download", "Cast"), timeout=timeout)
    _ensure_favorite(client, timeout=timeout)
    client.key("KEYCODE_BACK")
    client.wait_for("movies_after_detail", required=("streamvault.destination:movies", "Fixture Movie One"), timeout=timeout)
    client.tap_marker("Saved")
    client.wait_for("movies_saved", required=("streamvault.destination:movies", "Fixture Movie One", "Saved"), timeout=timeout)

    client.navigate("series", package, activity)
    client.wait_for(
        "series_browse",
        required=("streamvault.destination:series", "Fixture Series One", "Fixture Series Two"),
        timeout=timeout,
    )
    client.focus_and_activate_marker("Fixture Series One")
    client.wait_for("series_detail", required=("Fixture Series One", "Season 1"), timeout=timeout)
    _ensure_favorite(client, timeout=timeout)
    client.wait_for_any("series_favorite", ("Remove from favourites", "Remove from favorites"), timeout=timeout)
    for _ in range(3):
        client.shell("input", "swipe", "960", "900", "960", "260", "500")
    client.wait_for(
        "series_episodes",
        required=("Episodes (2)", "Fixture Series One - Pilot", "Fixture Series One - Second Signal"),
        timeout=timeout,
    )
    client.key("KEYCODE_BACK")
    client.wait_for("series_after_detail", required=("streamvault.destination:series", "Fixture Series One"), timeout=timeout)

    # Detail screens intentionally omit the shell; the preceding Back returns to Series browse
    # before the normal top-navigation path is exercised here.
    client.navigate("search", package, activity)
    client.wait_for_any(
        "search_empty",
        ("Search...", "Search everything", "Search the library"),
        timeout=timeout,
    )
    # SearchInput is already the focused TV control when Search opens; center activates its
    # editable field (a coordinate tap does not reliably switch TV read-only mode).
    client.key("KEYCODE_DPAD_CENTER")
    client.shell("input", "text", "Fixture")
    # Commit the TV text field before attempting to scroll the result column.  Without an IME
    # action, DPAD/swipe input remains owned by the editable control and the result rows stay
    # below the viewport.
    client.key("KEYCODE_ENTER")
    client.wait_for(
        "search_fixture",
        required=("5 results", "Live TV 1", "Movies 2", "Series 2"),
        timeout=timeout,
    )
    for _ in range(3):
        client.shell("input", "swipe", "960", "900", "960", "260", "500")
    client.wait_for(
        "search_fixture_content",
        required=("Fixture Movie One", "Fixture Series One"),
        timeout=timeout,
    )
    return [
        "home",
        "movies_browse",
        "movie_detail",
        "movies_saved",
        "series_browse",
        "series_detail",
        "search_fixture",
        "search_fixture_content",
    ]


def _has_marker(client: AdbClient, patterns: Sequence[str]) -> bool:
    dump = client.snapshot("favorite_probe")
    values = extract_ui_strings(dump)
    return any(re.search(pattern, value, re.IGNORECASE) for pattern in patterns for value in values)


def _ensure_favorite(client: AdbClient, *, timeout: float) -> None:
    remove_patterns = (r"Remove from favourites", r"Remove from favorites")
    add_patterns = (r"Add to favourites", r"Add to favorites")
    if _has_marker(client, remove_patterns):
        return
    if not _has_marker(client, add_patterns):
        raise CatalogValidationError("Detail screen did not expose a favorite toggle.")
    client.focus_and_activate_matching(add_patterns, timeout=timeout)
    client.wait_for_any("favorite_toggle", remove_patterns, timeout=timeout)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", help="Path to adb.exe; defaults to local.properties or E:\\androidSdk")
    parser.add_argument("--serial", help="ADB serial; otherwise use the first connected device")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY)
    parser.add_argument("--output-directory", default="build/catalog-validation")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    args = parser.parse_args(argv)

    root = Path(__file__).resolve().parents[1]
    output_directory = (root / args.output_directory).resolve()
    report = {
        "package": args.package,
        "serial": args.serial,
        "outputDirectory": str(output_directory),
        "passed": False,
    }
    try:
        adb = resolve_adb(root, args.adb)
        client = AdbClient(adb, args.serial, output_directory)
        client.check_device()
        report["serial"] = client.serial
        report["adb"] = str(adb)
        report["surfaces"] = run_journey(client, args.package, args.activity, args.timeout)
        report["passed"] = True
    except (CatalogValidationError, AssertionError, subprocess.TimeoutExpired) as exc:
        report["failure"] = str(exc)
        print(f"Catalog connected validation failed: {exc}", file=sys.stderr)
    finally:
        output_directory.mkdir(parents=True, exist_ok=True)
        (output_directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
