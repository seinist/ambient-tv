#!/usr/bin/env python3
"""Audit Monogram raw native/AAR bytes and exact per-ABI release APK size delta.

An AAR is not an APK-size estimate: Android may ship ELF files uncompressed.
Use matching baseline/Monogram release APKs for the final 15 MB budget gate.
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
MB = 1_000_000


def mb(n: int) -> str:
    return f"{n / MB:.2f} MB"


def entries(path: Path) -> dict[str, zipfile.ZipInfo]:
    with zipfile.ZipFile(path) as archive:
        items = [i for i in archive.infolist() if not i.is_dir()]
        result = {i.filename: i for i in items}
        if len(result) != len(items):
            raise ValueError(f"Duplicate ZIP entries in {path}")
        return result


def abi_libraries(items: dict[str, zipfile.ZipInfo], prefix: str) -> set[str]:
    return {name.split("/")[1] for name in items
            if name.startswith(prefix) and name.endswith(".so")
            and len(name.split("/")) >= 3}


def native_audit(aar: Path, abi: str, budget: float) -> tuple[dict, str]:
    files = entries(aar)
    so = f"jni/{abi}/libmonogram_mtproto.so"
    if so not in files:
        raise ValueError(f"{aar} has no {so}")
    info = files[so]
    result = {
        "mode": "native-aar", "abi": abi, "aar_bytes": aar.stat().st_size,
        "mtproto_elf_bytes": info.file_size,
        "mtproto_elf_zip_bytes": info.compress_size,
        "aar_abis": sorted(abi_libraries(files, "jni/")),
        "max_native_mb": budget,
        "passes_native_budget": info.file_size <= budget * MB,
        "apk_growth_measured": False,
    }
    md = (
        f"### Monogram native size: `{abi}`\n\n"
        "| Metric | Actual |\n|---|---:|\n"
        f"| MTProto ELF, raw (may be uncompressed in APK) | {mb(info.file_size)} |\n"
        f"| MTProto ELF, compressed inside AAR | {mb(info.compress_size)} |\n"
        f"| Complete AAR archive | {mb(aar.stat().st_size)} |\n"
        f"| Early raw ELF budget | {budget:g} MB — {'pass' if result['passes_native_budget'] else 'FAIL'} |\n\n"
        "**Not an APK delta.** Full APK including JNA, Rustls and R8 is checked separately.\n"
    )
    return result, md


def section(name: str) -> str:
    if name.startswith("lib/"):
        return "Native libraries"
    if name.startswith("classes") and name.endswith(".dex"):
        return "DEX bytecode"
    if name.startswith("res/") or name == "resources.arsc":
        return "Android resources"
    if name.startswith("assets/"):
        return "Assets"
    return "Other"


def apk_audit(base: Path, opt_in: Path, abi: str, budget: float) -> tuple[dict, str]:
    before, after = entries(base), entries(opt_in)
    old_abis, new_abis = abi_libraries(before, "lib/"), abi_libraries(after, "lib/")
    if old_abis != {abi} or new_abis != {abi}:
        raise ValueError(f"Expected matched single-ABI APKs ({abi}); baseline={sorted(old_abis)}, Monogram={sorted(new_abis)}")
    so = f"lib/{abi}/libmonogram_mtproto.so"
    if so in before or so not in after:
        raise ValueError(f"Monogram library must appear only in opt-in APK: {so}")
    sections: dict[str, int] = defaultdict(int)
    increases: list[tuple[str, int]] = []
    for name in set(before) | set(after):
        old_size = before[name].compress_size if name in before else 0
        new_size = after[name].compress_size if name in after else 0
        diff = new_size - old_size
        sections[section(name)] += diff
        if diff > 0:
            increases.append((name, diff))
    growth = opt_in.stat().st_size - base.stat().st_size
    passes = growth <= budget * MB
    biggest = sorted(increases, key=lambda item: -item[1])[:8]
    report = {
        "mode": "release-apk-delta", "abi": abi,
        "baseline_apk_bytes": base.stat().st_size,
        "monogram_apk_bytes": opt_in.stat().st_size,
        "growth_bytes": growth,
        "max_apk_growth_mb": budget,
        "passes_apk_budget": passes, "apk_growth_measured": True,
        "zip_contribution_delta_bytes": dict(sorted(sections.items())),
        "largest_positive_entry_deltas": [{"entry": name, "bytes": delta} for name, delta in biggest],
        "mtproto_stored_bytes": after[so].compress_size,
        "mtproto_raw_bytes": after[so].file_size,
    }
    lines = [
        f"### Monogram APK size delta: `{abi}`", "",
        "| Measurement | Size |", "|---|---:|",
        f"| Baseline release APK | {mb(base.stat().st_size)} |",
        f"| Monogram release APK | {mb(opt_in.stat().st_size)} |",
        f"| **APK growth** | **{mb(growth)}** |",
        f"| Additional APK budget | ≤ {budget:g} MB — {'pass' if passes else 'FAIL'} |",
        f"| MTProto ELF in APK (stored / raw) | {mb(after[so].compress_size)} / {mb(after[so].file_size)} |",
        "", "ZIP entry contributions (ZIP metadata can cause a small difference):",
        "", "| Section | Delta |", "|---|---:|",
    ]
    lines.extend(f"| {name} | {mb(delta)} |" for name, delta in sorted(sections.items()))
    lines.extend(["", "Largest increased entries:", "", "| Entry | Delta |", "|---|---:|"])
    lines.extend(f"| `{name}` | {mb(delta)} |" for name, delta in biggest)
    return report, "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", required=True, choices=ABIS)
    parser.add_argument("--aar", type=Path, help="Early native-only measurement")
    parser.add_argument("--base", type=Path, help="Release APK without Monogram")
    parser.add_argument("--monogram", type=Path, help="Release APK with Monogram")
    parser.add_argument("--max-native-mb", type=float, default=10.0)
    parser.add_argument("--max-apk-growth-mb", type=float, default=15.0)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    if not (args.aar or args.base or args.monogram):
        parser.error("Provide --aar or a baseline/Monogram APK pair")
    if bool(args.base) != bool(args.monogram):
        parser.error("--base and --monogram must be supplied together")
    if args.max_native_mb <= 0 or args.max_apk_growth_mb <= 0:
        parser.error("Size budgets must be positive")
    reports, notes = [], []
    try:
        if args.aar:
            report, note = native_audit(args.aar, args.abi, args.max_native_mb)
            reports.append(report)
            notes.append(note)
        if args.base and args.monogram:
            report, note = apk_audit(args.base, args.monogram, args.abi, args.max_apk_growth_mb)
            reports.append(report)
            notes.append(note)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"Monogram size audit failed: {exc}\n")
    output = "\n".join(notes)
    print(output)
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(output, encoding="utf-8")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps({"reports": reports}, indent=2) + "\n", encoding="utf-8")
    return 0 if all(r.get("passes_native_budget", True) and r.get("passes_apk_budget", True) for r in reports) else 1


if __name__ == "__main__":
    sys.exit(main())
