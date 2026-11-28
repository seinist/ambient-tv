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


MAIN_LABELS = {
    "rewind": "AmbientGame rewind core",
    "retro_run": "AmbientGame core run",
    "netplay": "AmbientGame netplay core",
    "rewind_seek": "AmbientGame rewind preview core",
}
WRAPPED_CALL = re.compile(
    r'\btraceGameCall\s*\(\s*"([^"]+)"\s*,\s*\[\s*&\s*\]\s*'
    r'\{\s*(core\s*->\s*retro_run|runCoreFrame)\s*\(\s*\)\s*;\s*\}\s*\)\s*;'
)
ANY_CALL = re.compile(r'\bcore\s*->\s*retro_run\s*\(')
HELPER_CALL = re.compile(r'\brunCoreFrame\s*\(')


def verify_main_trace(source):
    """Validate both direct traces and main's shared Vulkan reservation helper."""
    functions = list(FUNCTION.finditer(source))
    sections = {match[1]: source[match.end():functions[j+1].start() if j+1 < len(functions) else len(source)]
                for j, match in enumerate(functions)}
    expected = [MAIN_LABELS[label] for labels in LABELS.values() for label in labels]
    calls = WRAPPED_CALL.findall(source)
    if sorted(label for label, _ in calls) != sorted(expected):
        raise ValueError(f"Unexpected main trace paths: {calls}")
    for name, labels in LABELS.items():
        actual = [label for label, _ in WRAPPED_CALL.findall(sections.get(name, ""))]
        if actual != [MAIN_LABELS[label] for label in labels]:
            raise ValueError(f"Unexpected trace location in {name}: {actual}")
    if all(call == "runCoreFrame" for _, call in calls):
        helper = sections.get("runCoreFrame", "")
        if (len(ANY_CALL.findall(helper)) != 1 or len(ANY_CALL.findall(source)) != 1
                or len(HELPER_CALL.findall(source)) != len(calls) + 1):
            raise ValueError("Shared runCoreFrame boundary changed")
    elif (any(call == "runCoreFrame" for _, call in calls)
          or len(ANY_CALL.findall(source)) != len(calls)):
        raise ValueError("Unexpected direct core execution layout")
    return source


def instrument(source):
    if '#include "ambientgametrace.h"' in source:
        return verify_main_trace(source)
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
