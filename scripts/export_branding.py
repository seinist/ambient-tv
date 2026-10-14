#!/usr/bin/python3
"""Derive the SVG branding kit, then export PNGs using librsvg and Cairo.

Run with /usr/bin/python3 scripts/export_branding.py.
The approved combo SVG is the source of truth; no font installation is needed.
Requires Python GI, Rsvg 2.0 and Pycairo from the system packages.
"""

from copy import deepcopy
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BRANDING = ROOT / "branding"
SVG = BRANDING / "svg"
PNG = BRANDING / "png"
NAMESPACE = "http://www.w3.org/2000/svg"
NS = {"s": NAMESPACE}
ET.register_namespace("", NAMESPACE)


def element(tag, attributes=None, **kwargs):
    return ET.Element(f"{{{NAMESPACE}}}{tag}", attributes or {}, **kwargs)


def document(width, height, title, view_box=None):
    root = element("svg", {
        "width": str(width), "height": str(height),
        "viewBox": view_box or f"0 0 {width} {height}",
        "role": "img", "aria-label": title,
    })
    heading = element("title")
    heading.text = title
    root.append(heading)
    return root


def save_svg(name, root, directory=SVG):
    directory.mkdir(parents=True, exist_ok=True)
    ET.indent(root, space="  ")
    destination = directory / name
    ET.ElementTree(root).write(destination, encoding="utf-8", xml_declaration=True)
    return destination


def rectangle(x, y, width, height, fill, radius=None):
    attributes = {"x": str(x), "y": str(y), "width": str(width),
                  "height": str(height), "fill": fill}
    if radius is not None:
        attributes["rx"] = str(radius)
    return element("rect", attributes)


def embed(parent, source, x, y, width, height):
    nested = deepcopy(source)
    nested.attrib.update({"x": str(x), "y": str(y), "width": str(width), "height": str(height)})
    # Overview instances do not need the masters' reusable IDs.
    for node in nested.iter():
        node.attrib.pop("id", None)
    parent.append(nested)


def label(parent, text, x, y, size=15, color="#abb1bd", weight="400"):
    node = element("text", {"x": str(x), "y": str(y), "fill": color,
                           "font-family": "sans-serif", "font-size": str(size),
                           "font-weight": weight})
    node.text = text
    parent.append(node)


def make_svg_kit():
    combo = ET.parse(SVG / "ambient-tv-combo.svg").getroot()
    nodes = {node.attrib.get("id"): node for node in combo}
    required = {"brand-icon", "brand-wordmark", "brand-tv-badge", "brand-tv-label"}
    if not required.issubset(nodes):
        raise ValueError(f"Combo master must contain {sorted(required)}")

    # The monitor is identical in the compact icon and the combined logo.
    monitor = deepcopy(nodes["brand-icon"])
    monitor.attrib.pop("transform")
    for size, name in ((108, "ambient-tv-icon.svg"), (48, "ambient-tv-icon-48.svg")):
        icon = document(size, size, "Ambient TV icon", "0 0 48 48")
        icon.append(deepcopy(monitor))
        save_svg(name, icon)

    wordmark = document(152, 32, "Ambient TV wordmark")
    group = element("g", {"transform": "translate(-41 -4)"})
    for identifier in ("brand-wordmark", "brand-tv-badge", "brand-tv-label"):
        group.append(deepcopy(nodes[identifier]))
    wordmark.append(group)
    save_svg("ambient-tv-wordmark.svg", wordmark)

    banner = document(320, 180, "Ambient TV banner")
    banner.append(rectangle(0, 0, 320, 180, "#101115"))
    # Android TV launchers present apps as wide 16:9 banners. Use the wordmark
    # alone here; the separate monitor mark would make the lettering too small.
    embed(banner, wordmark, 28, 62, 264, 56)
    save_svg("ambient-tv-banner.svg", banner)

    foreground = document(108, 108, "Ambient TV adaptive launcher foreground")
    # Painted bounds lie inside the central 66-diameter safe circle.
    placement = element("g", {"transform": "translate(24 24) scale(1.25)"})
    placement.append(deepcopy(monitor))
    foreground.append(placement)
    save_svg("ambient-tv-launcher-foreground.svg", foreground)

    launcher = document(108, 108, "Ambient TV launcher icon")
    launcher.append(rectangle(0, 0, 108, 108, "#101115"))
    launcher.append(deepcopy(placement))
    save_svg("ambient-tv-launcher.svg", launcher)

    # Runtime SVGs use the app's accent for the same parts that are amber in
    # the export kit. White lettering and the dark TV label retain their colors.
    for kind in ("icon-48", "wordmark", "combo"):
        themed = ET.parse(SVG / f"ambient-tv-{kind}.svg").getroot()
        for node in themed.iter():
            for attribute in ("stroke", "fill"):
                if node.get(attribute, "").upper() == "#F0B441":
                    node.set(attribute, "currentColor")
        name = f"ambient-tv-{kind.removesuffix('-48')}.svg"
        save_svg(name, deepcopy(themed), SVG / "app")
        save_svg(name, themed, ROOT / "app/src/main/assets/branding")

    overview = document(1200, 940, "Ambient TV – freigegebenes Branding")
    overview.append(rectangle(0, 0, 1200, 940, "#0b0c0f"))
    label(overview, "AMBIENT TV / BRANDING", 48, 46, 13, "#F0B441", "600")
    label(overview, "Ein gemeinsamer Auftritt.", 48, 92, 32, "#f3f3f6", "600")
    label(overview, "SVG-Master und daraus exportierte PNGs", 48, 126, 17)

    overview.append(rectangle(48, 158, 1104, 258, "#13151a", 16))
    label(overview, "KOMBILOGO", 72, 190, 12)
    embed(overview, combo, 120, 222, 960, 200)

    overview.append(rectangle(48, 440, 714, 180, "#13151a", 16))
    label(overview, "WORTMARKE", 72, 474, 12)
    embed(overview, wordmark, 112, 493, 570, 120)
    overview.append(rectangle(786, 440, 366, 180, "#13151a", 16))
    label(overview, "ICON · 24 / 48 / 96 PX", 810, 474, 12)
    for size, x in ((24, 819), (48, 870), (96, 952)):
        embed(overview, icon, x, 539 - size / 2, size, size)

    overview.append(rectangle(48, 644, 714, 248, "#13151a", 16))
    label(overview, "TV-BANNER · 16:9", 72, 678, 12)
    embed(overview, banner, 221, 694, 352, 198)
    overview.append(rectangle(786, 644, 366, 248, "#13151a", 16))
    label(overview, "LAUNCHER", 810, 678, 12)
    embed(overview, launcher, 885, 711, 162, 162)
    label(overview, "Gelb #F0B441 · Weiß #F3F3F6 · TV-Schrift #181511 · Hintergrund #101115", 48, 925, 14)
    save_svg("branding-overview.svg", overview, BRANDING / "preview")


def export_platform_vectors():
    """Android launcher/banner resources derived from the same SVG geometry.

    In-app Compose components load the SVGs themselves. These VectorDrawables
    cover the splash icon, manifest banner and retained Android resource aliases.
    The phone launcher foreground is the 432px PNG copied from the same SVG.
    The converter deliberately accepts only primitives used by our masters.
    """
    android_ns = "http://schemas.android.com/apk/res/android"
    ET.register_namespace("android", android_ns)

    def android(tag, values):
        return ET.Element(tag, {f"{{{android_ns}}}{key}": str(value) for key, value in values.items()})

    def append_node(parent, node, inherited):
        tag = node.tag.rsplit("}", 1)[-1]
        if tag in ("title", "desc"):
            return
        style = dict(inherited)
        style.update({key: value for key, value in node.attrib.items()
                      if key in ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin")})
        if tag == "svg":
            vx, vy, vw, vh = map(float, node.attrib["viewBox"].split())
            if vx != 0 or vy != 0:
                raise ValueError("Only zero-origin SVG viewports are supported")
            target = android("group", {
                "translateX": node.get("x", "0"), "translateY": node.get("y", "0"),
                "scaleX": float(node.attrib["width"]) / vw,
                "scaleY": float(node.attrib["height"]) / vh,
            })
            parent.append(target)
        elif tag == "g":
            target = parent
            for name, arguments in re.findall(r"(translate|scale)\(([^)]*)\)", node.get("transform", "")):
                numbers = list(map(float, arguments.replace(",", " ").split()))
                x = numbers[0]
                y = numbers[1] if len(numbers) > 1 else (0 if name == "translate" else x)
                transformed = android("group", {name + "X": x, name + "Y": y})
                target.append(transformed)
                target = transformed
        elif tag in ("path", "rect"):
            # Paths can carry their own translation/scaling, notably glyph outlines.
            if "transform" in node.attrib:
                wrapper = element("g", {"transform": node.attrib["transform"]})
                child = deepcopy(node)
                del child.attrib["transform"]
                wrapper.append(child)
                append_node(parent, wrapper, style)
                return
            if tag == "path":
                data = node.attrib["d"]
            else:
                x, y = float(node.get("x", "0")), float(node.get("y", "0"))
                w, h = float(node.attrib["width"]), float(node.attrib["height"])
                r = float(node.get("rx", "0"))
                if r:
                    data = (f"M{x+r},{y}H{x+w-r}A{r},{r} 0 0 1 {x+w},{y+r}V{y+h-r}"
                            f"A{r},{r} 0 0 1 {x+w-r},{y+h}H{x+r}A{r},{r} 0 0 1 {x},{y+h-r}"
                            f"V{y+r}A{r},{r} 0 0 1 {x+r},{y}Z")
                else:
                    data = f"M{x},{y}h{w}v{h}h{-w}Z"
            attributes = {"pathData": data, "fillColor": style.get("fill", "#000000")}
            if attributes["fillColor"] == "none":
                attributes["fillColor"] = "#00000000"
            for key, field in (("stroke", "strokeColor"), ("stroke-width", "strokeWidth"),
                               ("stroke-linecap", "strokeLineCap"), ("stroke-linejoin", "strokeLineJoin")):
                if key in style:
                    attributes[field] = "#00000000" if style[key] == "none" else style[key]
            parent.append(android("path", attributes))
            return
        else:
            raise ValueError(f"Unsupported SVG primitive: {tag}")
        for child in node:
            append_node(target, child, style)

    outputs = {
        "ic_ambient_tv.xml": ("icon-48", 24, 24),
        "ic_ambient_tv_lg.xml": ("icon-48", 48, 48),
        "ic_brand_wordmark.xml": ("wordmark", 152, 32),
        "app_banner.xml": ("banner", 320, 180),
        "ic_launcher_foreground.xml": ("launcher-foreground", 108, 108),
    }
    directory = ROOT / "app/src/main/res/drawable"
    for filename, (kind, width, height) in outputs.items():
        source = ET.parse(SVG / f"ambient-tv-{kind}.svg").getroot()
        _, _, vw, vh = map(float, source.attrib["viewBox"].split())
        vector = android("vector", {"width": f"{width}dp", "height": f"{height}dp",
                                    "viewportWidth": vw, "viewportHeight": vh})
        for child in source:
            append_node(vector, child, {})
        ET.indent(vector, space="    ")
        ET.ElementTree(vector).write(directory / filename, encoding="utf-8", xml_declaration=True)


def render_png(source, destination, width):
    import gi
    gi.require_version("Rsvg", "2.0")
    from gi.repository import Rsvg
    import cairo

    root = ET.parse(source).getroot()
    _, _, source_width, source_height = map(float, root.attrib["viewBox"].split())
    height = round(width * source_height / source_width)
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, width, height)
    viewport = Rsvg.Rectangle()
    viewport.x, viewport.y, viewport.width, viewport.height = 0, 0, width, height
    handle = Rsvg.Handle.new_from_file(str(source))
    if not handle.render_document(cairo.Context(surface), viewport):
        raise RuntimeError(f"Could not render {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    surface.write_to_png(str(destination))
    print(f"  {destination.relative_to(ROOT)} ({width} × {height})")


def export_png_kit():
    exports = {
        "icon": (24, 48, 96, 192, 512),
        "wordmark": (200, 400, 800),
        "combo": (400, 800, 1200),
        "banner": (320, 640, 1280),
        "launcher": (48, 72, 96, 144, 192, 512),
        "launcher-foreground": (432,),
    }
    for kind, sizes in exports.items():
        source = SVG / f"ambient-tv-{kind}.svg"
        for size in sizes:
            render_png(source, PNG / kind / f"ambient-tv-{kind}-{size}.png", size)
    render_png(BRANDING / "preview/branding-overview.svg",
               BRANDING / "preview/branding-overview.png", 1200)
    # Phone launcher samples this bitmap (adaptive foreground). Keep only the
    # xxxhdpi layer so lower buckets cannot upscale a small PNG.
    launcher_dir = ROOT / "app/src/main/res/mipmap-xxxhdpi"
    launcher_dir.mkdir(parents=True, exist_ok=True)
    foreground = PNG / "launcher-foreground" / "ambient-tv-launcher-foreground-432.png"
    (launcher_dir / "ic_launcher_foreground.png").write_bytes(foreground.read_bytes())
    print(f"  app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png (432 × 432)")


if __name__ == "__main__":
    print("1. Deriving SVG masters from ambient-tv-combo.svg")
    make_svg_kit()
    export_platform_vectors()
    print("2. Exporting PNGs from the completed SVG masters")
    export_png_kit()
