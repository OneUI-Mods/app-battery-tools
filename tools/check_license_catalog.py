#!/usr/bin/env python3
"""Verify the browsable license catalog against the resolved release dependencies."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
runtime = set((root / "app/build/runtime-dependencies.txt").read_text().splitlines())
assets = root / "app/src/main/assets"
items = json.loads((assets / "legal/libraries.json").read_text())
listed = {
    item["id"] + ":" + item["version"]
    for item in items
    if ":" in item["id"] and not item.get("reference", False)
}
if listed != runtime:
    raise SystemExit(
        "License catalog must match release dependencies. Update legal/libraries.json."
    )
for item in items:
    if not (assets / item["asset"]).is_file():
        raise SystemExit("Missing license file: " + item["asset"])
    notice = item.get("notice", "")
    if notice.startswith("legal/") and not (assets / notice).is_file():
        raise SystemExit("Missing attribution file: " + notice)
print(f"License catalog verified: {len(runtime)} runtime components.")
