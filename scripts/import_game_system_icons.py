#!/usr/bin/env python3
"""Reproduce the pinned RetroArch Monochrome SVG -> Android vector import.

Uses only the Python standard library. This intentionally supports the shapes,
paint and clipping in the vendored, hash-checked source set; unsupported SVG
features fail instead of silently dropping artwork. Original path commands are
retained. General affine matrices become nested Android translate/rotate/scale
groups, including the sheared Wii artwork, without rasterization or path fitting.

Run from any directory; --check verifies generated output without writing it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "third_party_assets/game_system_icons"
ANDROID = "http://schemas.android.com/apk/res/android"
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("android", ANDROID)
NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"


def number(value: float) -> str:
    if abs(value) < 1e-12:
        return "0"
    return format(value, ".12g")


def numbers(value: str) -> list[float]:
    values = [float(item) for item in re.findall(NUMBER, value)]
    remainder = re.sub(NUMBER, "", value).replace(",", "").strip()
    if remainder or not all(math.isfinite(item) for item in values):
        raise ValueError(f"Unsupported SVG number list: {value!r}")
    return values


def android_attributes(**values: str) -> dict[str, str]:
    return {f"{{{ANDROID}}}{key}": value for key, value in values.items()}


def group(parent: ET.Element, **values: float) -> ET.Element:
    return ET.SubElement(parent, "group", android_attributes(
        **{key: number(value) for key, value in values.items()},
    ))


def matrix_group(parent: ET.Element, values: list[float]) -> ET.Element:
    if len(values) != 6:
        raise ValueError("An SVG matrix must contain six numbers")
    a, b, c, d, tx, ty = values
    if abs(b) < 1e-12 and abs(c) < 1e-12:
        return group(parent, translateX=tx, translateY=ty, scaleX=a, scaleY=d)

    # A = R(phi) * diag(s1, s2) * R(-theta). Diagonalize A^T A;
    # s2 carries any reflection, so both R groups remain proper rotations.
    theta = .5 * math.atan2(2 * (a * c + b * d), a * a + b * b - c * c - d * d)
    ct, st = math.cos(theta), math.sin(theta)
    ux, uy = a * ct + c * st, b * ct + d * st
    s1 = math.hypot(ux, uy)
    if s1 < 1e-12:
        raise ValueError("Degenerate affine transform")
    s2 = (a * d - b * c) / s1
    phi = math.atan2(uy, ux)
    cp, sp = math.cos(phi), math.sin(phi)
    reconstructed = (
        cp * s1 * ct + sp * s2 * st,
        sp * s1 * ct - cp * s2 * st,
        cp * s1 * st - sp * s2 * ct,
        sp * s1 * st + cp * s2 * ct,
    )
    if not all(math.isclose(actual, expected, rel_tol=1e-9, abs_tol=1e-9)
               for actual, expected in zip(reconstructed, (a, b, c, d))):
        raise ValueError("Affine decomposition did not preserve the source matrix")
    outer = group(parent, translateX=tx, translateY=ty, rotation=math.degrees(phi), scaleX=s1, scaleY=s2)
    return group(outer, rotation=-math.degrees(theta))


def transforms(parent: ET.Element, value: str) -> ET.Element:
    position = 0
    for match in re.finditer(r"([A-Za-z]+)\s*\(([^)]*)\)", value):
        if value[position:match.start()].strip(" ,\t\r\n"):
            raise ValueError(f"Unsupported SVG transform: {value!r}")
        operation, arguments = match.group(1), numbers(match.group(2))
        if operation == "matrix":
            parent = matrix_group(parent, arguments)
        elif operation == "translate" and len(arguments) in (1, 2):
            parent = group(parent, translateX=arguments[0], translateY=arguments[1] if len(arguments) == 2 else 0)
        elif operation == "scale" and len(arguments) in (1, 2):
            parent = group(parent, scaleX=arguments[0], scaleY=arguments[1] if len(arguments) == 2 else arguments[0])
        else:
            raise ValueError(f"Unsupported SVG transform: {operation}")
        position = match.end()
    if value[position:].strip(" ,\t\r\n"):
        raise ValueError(f"Unsupported SVG transform: {value!r}")
    return parent


def properties(value: str) -> dict[str, str]:
    return {key.strip(): val.strip() for declaration in value.split(";") if ":" in declaration
            for key, val in [declaration.split(":", 1)]}


def convert(source: Path, asset: dict, manifest: dict) -> bytes:
    svg = ET.parse(source).getroot()
    if svg.tag != f"{{{SVG}}}svg":
        raise ValueError("Expected an SVG document")
    ids = {node.attrib["id"]: node for node in svg.iter() if "id" in node.attrib}
    classes: dict[str, dict[str, str]] = {}
    for style in svg.findall(f"{{{SVG}}}style"):
        css = "".join(style.itertext())
        consumed = ""
        for match in re.finditer(r"\.([\w-]+)\s*\{([^}]+)\}", css):
            classes[match.group(1)] = properties(match.group(2))
            consumed += match.group(0)
        if re.sub(r"\s", "", consumed) != re.sub(r"\s", "", css):
            raise ValueError("Unsupported SVG stylesheet")

    vector = ET.Element("vector", android_attributes(
        width="64dp", height="64dp", viewportWidth="64", viewportHeight="64",
    ))
    ET.SubElement(vector, "clip-path", android_attributes(pathData="M0,0H64V64H0Z"))
    if "viewBox" in svg.attrib:
        view_box = numbers(svg.attrib["viewBox"])
    else:
        view_box = [0., 0., float(svg.attrib["width"].removesuffix("px")),
                    float(svg.attrib["height"].removesuffix("px"))]
    if len(view_box) != 4 or view_box[2] <= 0 or view_box[3] <= 0 or view_box[2] != view_box[3]:
        raise ValueError("Expected a square source viewport")
    x, y, width, height = view_box
    parent = group(vector, scaleX=64 / width, scaleY=64 / height,
                   translateX=-x * 64 / width, translateY=-y * 64 / height)

    def walk(node: ET.Element, destination: ET.Element, inherited: dict[str, str]) -> None:
        if not node.tag.startswith(f"{{{SVG}}}"):
            return  # Inkscape/Sodipodi editing metadata has no rendered geometry.
        tag = node.tag.removeprefix(f"{{{SVG}}}")
        if tag in {"defs", "metadata", "title", "desc", "style"}:
            return
        if tag not in {"svg", "g", "path", "polygon"}:
            raise ValueError(f"Unsupported SVG shape: {tag}")
        paint = dict(inherited)
        paint.update({key: value for key, value in node.attrib.items()
                      if key in {"fill", "fill-opacity", "fill-rule", "opacity", "stroke", "display", "clip-path"}})
        for name in node.attrib.get("class", "").split():
            if name not in classes:
                raise ValueError(f"Unknown SVG class: {name}")
            paint.update(classes[name])
        paint.update(properties(node.attrib.get("style", "")))
        if paint.get("display") == "none":
            return
        if float(paint.get("opacity", "1")) != 1 or float(paint.get("fill-opacity", "1")) != 1:
            raise ValueError("Translucent geometry requires a different import")
        clip = paint.pop("clip-path", None)
        if tag == "g" or "transform" in node.attrib or clip:
            destination = ET.SubElement(destination, "group")
        destination = transforms(destination, node.attrib.get("transform", ""))
        if clip:
            match = re.fullmatch(r"url\(#([^)]*)\)", clip)
            definition = ids.get(match.group(1)) if match else None
            if definition is None or definition.attrib.get("clipPathUnits", "userSpaceOnUse") != "userSpaceOnUse":
                raise ValueError(f"Unsupported clip path: {clip}")
            shapes = list(definition)
            if len(shapes) != 1 or shapes[0].tag != f"{{{SVG}}}path" or "transform" in shapes[0].attrib:
                raise ValueError("Expected one untransformed clip path")
            ET.SubElement(destination, "clip-path", android_attributes(pathData=shapes[0].attrib["d"]))
        if tag in {"svg", "g"}:
            for child in node:
                walk(child, destination, paint)
            return
        if paint.get("fill") == "none":
            return
        if paint.get("fill", "#000000").lower() not in {"#fff", "#ffffff", "white"}:
            raise ValueError("Only the source theme's opaque white geometry is accepted")
        if paint.get("stroke", "none") != "none" and float(paint.get("stroke-width", "1")) != 0:
            raise ValueError("Stroked geometry requires a different import")
        if tag == "polygon":
            coordinates = numbers(node.attrib["points"])
            if len(coordinates) < 6 or len(coordinates) % 2:
                raise ValueError("Invalid polygon")
            data = "M" + " ".join(number(value) for value in coordinates) + "Z"
        else:
            data = node.attrib["d"].strip()
        fill_rule = paint.get("fill-rule", "nonzero")
        if fill_rule not in {"nonzero", "evenodd"}:
            raise ValueError(f"Unsupported fill rule: {fill_rule}")
        ET.SubElement(destination, "path", android_attributes(
            fillColor="#FFFFFFFF", fillType="evenOdd" if fill_rule == "evenodd" else "nonZero", pathData=data,
        ))

    walk(svg, parent, {})
    if not list(vector.iter("path")):
        raise ValueError("Icon has no painted geometry")
    ET.indent(vector, space="    ")
    notice = (
        "<!--\n"
        "    RetroArch Assets / XMB Monochrome, CC BY 4.0.\n"
        f"    Source: {asset['upstream_path']}\n"
        f"    Revision: {manifest['upstream_commit']}\n"
        "    Native vector conversion; source geometry retained.\n"
        "    Credits and license: third_party_assets/game_system_icons/README.md\n"
        "    Regenerate with scripts/import_game_system_icons.py.\n"
        "-->\n"
    )
    return ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + notice +
            ET.tostring(vector, encoding="unicode", short_empty_elements=True) + "\n").encode()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify output without changing files")
    args = parser.parse_args()
    manifest = json.loads((ASSETS / "manifest.json").read_text())
    license_digest = hashlib.sha256((ASSETS / manifest["license_file"]).read_bytes()).hexdigest()
    if license_digest != manifest["license_sha256"]:
        raise ValueError("The vendored upstream license has changed")
    generated = []
    for asset in manifest["assets"]:
        source = ASSETS / asset["source_file"]
        if hashlib.sha256(source.read_bytes()).hexdigest() != asset["source_sha256"]:
            raise ValueError(f"Unpinned source change: {source}")
        generated.append((REPO / asset["output"], convert(source, asset, manifest)))
    for fallback in manifest["fallbacks"]:
        if hashlib.sha256((REPO / fallback["output"]).read_bytes()).hexdigest() != fallback["sha256"]:
            raise ValueError(f"Original fallback changed: {fallback['system_id']}")
    different = [str(path.relative_to(REPO)) for path, content in generated
                 if not path.exists() or path.read_bytes() != content]
    if args.check:
        if different:
            print("Generated icons need updating:\n" + "\n".join(different), file=sys.stderr)
            return 1
    else:
        for path, content in generated:
            path.write_bytes(content)
    print(f"{'Verified' if args.check else 'Generated'} {len(generated)} native vectors; "
          f"{len(manifest['fallbacks'])} original fallback retained.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
