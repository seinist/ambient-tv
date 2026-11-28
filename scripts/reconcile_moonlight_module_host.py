#!/usr/bin/env python3
"""Apply/check the module integration at the final Moonlight merge boundary.

Only exact reviewed anchors are replaced. No native optimization code is
copied or modified. A changed anchor requires reconciliation, not guessing.
"""
import argparse
from pathlib import Path

PROBE = "com.ambient.tv.modules.MoonlightModuleAvailability"
FEATURE = "com.ambient.tv.moonlight.MoonlightFeature"
CHANGES = {
    "scripts/fetch_moonlight_sources.sh": [
        ('    printf \'Moonlight %s (%s) already prepared\\n\' "$TAG" "$REF"',
         '    python3 "$ROOT/scripts/patch_moonlight_module_loader.py" "$DEST"\n'
         '    printf \'Moonlight %s (%s) already prepared\\n\' "$TAG" "$REF"'),
        ('# Swap in the prepared tree only after every check passed.',
         'python3 "$ROOT/scripts/patch_moonlight_module_loader.py" "$WORK"\n'
         '# Swap in the prepared tree only after every check passed.'),
    ],
    "app/src/main/java/com/ambient/tv/moonlight/MoonlightFeature.kt": [
        ("val nativeAvailable: Boolean get() = BuildConfig.MOONLIGHT_NATIVE",
         f"val nativeAvailable: Boolean get() = {PROBE}.isAvailable"),
        ("val setupAvailable: Boolean get() = BuildConfig.MOONLIGHT_EXPERIMENTAL",
         "val setupAvailable: Boolean get() = BuildConfig.MOONLIGHT_EXPERIMENTAL && "
         "(!BuildConfig.MOONLIGHT_NATIVE || nativeAvailable)"),
    ],
    "app/src/main/java/com/ambient/tv/AmbientTvApplication.kt": [
        (f"        if ({FEATURE}.nativeAvailable) moonlightPreloader.get().start()",
         "        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {\n"
         f"            if ({PROBE}.probe()) moonlightPreloader.get().start()\n"
         "        }"),
    ],
    "app/src/main/java/com/ambient/tv/ui/moonlight/MoonlightStreamActivity.kt": [
        ("if (!BuildConfig.MOONLIGHT_NATIVE || hostId == null || appId <= 0)",
         f"if (!{FEATURE}.nativeAvailable || hostId == null || appId <= 0)"),
    ],
    "app/src/moonlight/java/com/ambient/tv/moonlight/MoonlightStreamEngine.kt": [
        ("if (!surface.isValid || appId <= 0 || !prefs.moonlightEnabled) return false",
         "if (!MoonlightFeature.nativeAvailable || !surface.isValid || appId <= 0 || !prefs.moonlightEnabled) return false"),
    ],
    "app/src/moonlight/java/com/ambient/tv/moonlight/MoonlightSystemTransport.kt": [
        ("    val available = true", "    val available: Boolean get() = MoonlightFeature.nativeAvailable"),
        ("    fun newPairingPin(): String = backend.pairingPin()",
         "    fun newPairingPin(): String { check(available); return backend.pairingPin() }"),
        ("    suspend fun pair(hostId: String, pin: String): MoonlightPairResult = backend.pair(hostId, pin)",
         "    suspend fun pair(hostId: String, pin: String): MoonlightPairResult =\n"
         "        if (available) backend.pair(hostId, pin) else MoonlightPairResult.Unavailable"),
    ],
    "app/src/main/java/com/ambient/tv/ui/moonlight/MoonlightSettingsViewModel.kt": [
        ("    private var pairJob: Job? = null",
         "    private var pairJob: Job? = null\n\n"
         "    init {\n"
         "        viewModelScope.launch {\n"
         "            com.ambient.tv.modules.SystemModuleAvailability.states.collect {\n"
         "                mutable.update { it.copy(nativeAvailable = transport.available) }\n"
         "            }\n"
         "        }\n"
         "    }"),
    ],
}


def reconcile(root, check=False):
    updates = {}
    for relative, changes in CHANGES.items():
        path = root / relative
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Final Moonlight source not present: {relative}")
        content = path.read_text()
        original = content
        for before, after in changes:
            if content.count(after) == 1:
                continue
            if check or content.count(before) != 1:
                raise ValueError(f"Moonlight integration anchor requires review: {relative}: {before[:80]}")
            content = content.replace(before, after, 1)
        if original != content:
            updates[path] = content
    # Validate all anchors before editing any file.
    for path, content in updates.items():
        path.write_text(content)
    return len(updates)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(f"Moonlight integration: {reconcile(args.root, args.check)} updated files")
