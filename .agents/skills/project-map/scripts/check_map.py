#!/usr/bin/env python3
"""Check only mechanical project-map invariants; do not infer product status."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[4]
MAP = ROOT / "docs/PROJECT_MAP.md"
MODULES = ROOT / "backend/modules"


def markdown_paths(text: str) -> set[str]:
    candidates = set(re.findall(r"`([^`]+)`", text))
    return {
        value.rstrip("/")
        for value in candidates
        if "/" in value
        and "<" not in value
        and not value.startswith(("src/", "capability/"))
        and "*" not in value
    }


def main() -> int:
    errors: list[str] = []
    if not MAP.is_file():
        print("ERROR missing docs/PROJECT_MAP.md")
        return 1

    for relative in sorted(markdown_paths(MAP.read_text(encoding="utf-8"))):
        if not (ROOT / relative).exists():
            errors.append(f"map path does not exist: {relative}")

    module_names = []
    for module in sorted(MODULES.glob("module-*")):
        if not module.is_dir():
            continue
        module_names.append(module.name)
        if not (module / "capability/CAPABILITY.md").is_file():
            errors.append(f"module capability missing: {module.relative_to(ROOT)}")

    pom = (ROOT / "backend/pom.xml").read_text(encoding="utf-8")
    for module_name in module_names:
        if f"<module>modules/{module_name}</module>" not in pom:
            errors.append(f"backend/pom.xml does not list: {module_name}")

    if errors:
        for error in errors:
            print(f"ERROR {error}")
        return 1
    print(f"OK project map paths and {len(module_names)} module capabilities")
    return 0


if __name__ == "__main__":
    sys.exit(main())
