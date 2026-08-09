#!/usr/bin/env python3
"""Export MyRoutin's latest aggregated ModelRadar cache into the APK assets."""

from __future__ import annotations

import argparse
import json
import subprocess
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = PROJECT_ROOT / "app/src/main/assets/model_radar_snapshot.json"
DEFAULT_PACKAGE = "com.hss.myroutin"
SNAPSHOT_KEY = "snapshot_v2"


def read_device_snapshot(package_name: str) -> dict[str, Any]:
    """Read the public aggregated snapshot from a connected debuggable app."""
    command = [
        "adb",
        "shell",
        "run-as",
        package_name,
        "cat",
        "shared_prefs/model_radar_cache.xml",
    ]
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as exception:
        details = (exception.stderr or exception.stdout or "adb returned no error details").strip()
        raise RuntimeError(f"Unable to read ModelRadar cache via adb: {details}") from exception
    root = ElementTree.fromstring(result.stdout)
    snapshot_element = root.find(f"string[@name='{SNAPSHOT_KEY}']")
    if snapshot_element is None or not snapshot_element.text:
        raise ValueError(f"Missing {SNAPSHOT_KEY} in model_radar_cache.xml")
    snapshot = json.loads(snapshot_element.text)
    validate_snapshot(snapshot)
    return snapshot


def validate_snapshot(snapshot: dict[str, Any]) -> None:
    """Reject empty or incompatible caches before they are shipped in the APK."""
    if snapshot.get("schema") != 3:
        raise ValueError(f"Unsupported cache schema: {snapshot.get('schema')}")
    if not isinstance(snapshot.get("fetchedAt"), int) or snapshot["fetchedAt"] <= 0:
        raise ValueError("Snapshot fetchedAt must be a positive integer")
    for field_name in ("recommendations", "models", "efficiencyPoints"):
        value = snapshot.get(field_name)
        if not isinstance(value, list) or not value:
            raise ValueError(f"Snapshot {field_name} must be a non-empty list")
    if not any(point.get("recentRuns24h") is not None for point in snapshot["efficiencyPoints"]):
        raise ValueError("Snapshot has no per-effort recentRuns24h metrics")


def parse_args() -> argparse.Namespace:
    """Parse optional package and output overrides for release preparation."""
    parser = argparse.ArgumentParser(
        description="Export the latest MyRoutin ModelRadar cache into APK assets."
    )
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def main() -> int:
    """Export, validate, and format the bundled snapshot deterministically."""
    args = parse_args()
    snapshot = read_device_snapshot(args.package)
    output_path = args.output.resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        "Updated "
        f"{output_path} from fetchedAt={snapshot['fetchedAt']} "
        f"with {len(snapshot['efficiencyPoints'])} efficiency points"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
