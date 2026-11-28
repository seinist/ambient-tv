"""Regression: VLC's env -i Meson setup must retain only wrapper inputs."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "native/vlc-aar/patches/meson-ccache-environment.patch"
WRAPPER = ROOT / "scripts/vlc_ccache_compiler.sh"


class VlcMesonCacheEnvironmentTest(unittest.TestCase):
    def test_sanitized_meson_environment_with_and_without_cache(self):
        # Exercise the actual make expression from the production patch.
        lines = PATCH.read_text().splitlines()
        added = [line[1:] for line in lines if line.startswith("+") and not line.startswith("+++")]
        expression = "\n".join(added[added.index(next(x for x in added if x.startswith("AMBIENT_MESON_CCACHE_ENV ="))):])
        expression += "\n\tmeson setup -Dpkg_config_path=\"$(PKG_CONFIG_PATH)\" $(MESONFLAGS)\n"
        with tempfile.TemporaryDirectory(prefix="vlc meson ") as temp:
            root = Path(temp)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            compiler = root / "ndk-clang"
            cache = root / "cache"

            def executable(path, body):
                path.write_text("#!/bin/sh\nset -eu\n" + body)
                path.chmod(0o755)

            # The cache mock checks the wrapper's actual component and config.
            executable(bin_dir / "ccache", """
test "$CCACHE_DIR" = "$AMBIENT_VLC_CCACHE_ROOT/contrib"
test "$CCACHE_COMPILERCHECK" = content
test "$CCACHE_MAXSIZE" = 123M
printf 'cached invocation\\n'
exec "$@"
""")
            for path in (compiler, Path(str(compiler) + "++")):
                executable(path, 'printf "compiler=%s args=%s\\n" "$0" "$*"\n')
            for name in ("clang", "clang++"):
                (bin_dir / name).symlink_to(WRAPPER)
            executable(bin_dir / "meson", """
test -z "${CFLAGS+x}"
test -z "${CC+x}"
test -z "${AMBIENT_UNRELATED+x}"
if [ -n "${AMBIENT_VLC_REAL_CLANG:-}" ]; then
    test -n "$HOME"
    clang --version
    clang++ --version
    clang -Werror -c probe.c
    test ! -d "$AMBIENT_VLC_CCACHE_ROOT"
    clang -c normal.c
    clang++ -c normal.cpp
else
    test -z "${AMBIENT_VLC_CCACHE_ROOT+x}"
    printf 'uncached\\n'
fi
""".replace("\\${", "${"))
            makefile = root / "Makefile"
            makefile.write_text(expression + "\nall:\n\t@$(MESON)\n")
            cwd = root / "contrib" / "test"
            cwd.mkdir(parents=True)
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                       HOME=str(root), CFLAGS="should-not-leak", CC="should-not-leak",
                       AMBIENT_UNRELATED="should-not-leak")
            env.update(AMBIENT_VLC_REAL_CLANG=str(compiler), AMBIENT_VLC_CCACHE_ROOT=str(cache),
                       AMBIENT_VLC_CCACHE_MAXSIZE="123M")
            result = subprocess.run(["make", "-f", str(makefile)], cwd=cwd,
                                    env=env, text=True, capture_output=True, check=True)
            self.assertIn(f"compiler={compiler} args=--version", result.stdout)
            self.assertIn(f"compiler={compiler}++ args=--version", result.stdout)
            self.assertIn(f"compiler={compiler} args=-Werror -c probe.c", result.stdout)
            self.assertIn(f"compiler={compiler} args=-c normal.c", result.stdout)
            self.assertIn(f"compiler={compiler}++ args=-c normal.cpp", result.stdout)
            self.assertEqual(2, result.stdout.count("cached invocation"))
            self.assertTrue((cache / "contrib").is_dir())
            for key in ("AMBIENT_VLC_REAL_CLANG", "AMBIENT_VLC_CCACHE_ROOT", "AMBIENT_VLC_CCACHE_MAXSIZE"):
                env.pop(key)
            result = subprocess.run(["make", "-f", str(makefile)], cwd=cwd,
                                    env=env, text=True, capture_output=True, check=True)
            self.assertEqual("uncached", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
