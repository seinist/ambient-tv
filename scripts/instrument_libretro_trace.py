"""Instrument the four known core-call boundaries after the pinned patch series."""
import re
import sys
from pathlib import Path

FUNCTION = re.compile(r"^(?:void|bool) LibretroDroid::(\w+)\(", re.M)
CALL = re.compile(r"^([ \t]*)core->retro_run\(\);[ \t]*$", re.M)
LABELS = {
    "step": ("rewind", "retro_run"),
    "stepForNetplay": ("netplay",),
    "showRewindState": ("rewind_seek",),
}


def instrument(source):
    if '#include <android/trace.h>' not in source or "ATrace_beginSection(" in source:
        raise ValueError("Unexpected tracing include or existing instrumentation")
    functions = list(FUNCTION.finditer(source))
    replacements = []
    visited = set()
    for i, function in enumerate(functions):
        name = function[1]
        if name not in LABELS:
            continue
        if name in visited:
            raise ValueError(f"Duplicate trace function {name}")
        visited.add(name)
        end = functions[i + 1].start() if i + 1 < len(functions) else len(source)
        calls = list(CALL.finditer(source, function.end(), end))
        if len(calls) != len(LABELS[name]):
            raise ValueError(f"Unexpected retro_run count in {name}: {len(calls)}")
        for call, label in zip(calls, LABELS[name]):
            indent = call[1]
            body = [
                "{",
                "    const bool traceEnabled = ATrace_isEnabled();",
                f'    if (traceEnabled) ATrace_beginSection("AmbientTV:Libretro/{label}");',
                "    core->retro_run();",
                "    if (traceEnabled) ATrace_endSection();",
                "}",
            ]
            replacements.append((call.start(), call.end(), "\n".join(indent + line for line in body)))
    if visited != LABELS.keys() or len(list(CALL.finditer(source))) != len(replacements):
        raise ValueError("Unexpected native core execution layout; review the trace instrumentation")
    for start, end, replacement in sorted(replacements, reverse=True):
        source = source[:start] + replacement + source[end:]
    return source


if __name__ == "__main__":
    path = Path(sys.argv[1])
    path.write_text(instrument(path.read_text()))
