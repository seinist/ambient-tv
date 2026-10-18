import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("jq"), "jq is required for license bundling")
class LicenseDownloadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copyfile(Path(__file__).resolve().parents[1] / "vendor_svp_licenses.sh",
                        scripts / "vendor_svp_licenses.sh")
        crate = self.root / "native/ambientsvp"
        crate.mkdir(parents=True)
        (crate / "NOTICE.txt").write_text("notice")
        dependency = self.root / "registry/src/example"
        dependency.mkdir(parents=True)
        (dependency / ".cargo_vcs_info.json").write_text(json.dumps({"git": {"sha1": "a" * 40}}))
        metadata = {"packages": [{"name": "example", "version": "1.0", "license": "MIT",
                    "repository": "https://github.com/example/example",
                    "manifest_path": str(dependency / "Cargo.toml")}]}
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.executable("cargo", "#!/bin/sh\nprintf '%s' '" + json.dumps(metadata) + "'\n")
        self.executable("curl", r"""#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as out:
    out.write(json.dumps(args) + '\n')
if os.environ.get('FAIL_DOWNLOAD') == '1':
    if '-o' in args:
        pathlib.Path(args[args.index('-o') + 1]).write_text('partial')
        sys.exit(22)
if '-o' in args:
    pathlib.Path(args[args.index('-o') + 1]).write_text('MIT license')
else:
    print(json.dumps({'tree': [{'type': 'blob', 'path': 'LICENSE'}]}))
""")
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        CALLS=str(self.root / 'calls'))
        self.env.pop('GH_TOKEN', None)
        self.env.pop('GITHUB_TOKEN', None)

    def executable(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def run_bundle(self):
        return subprocess.run(['bash', str(self.root / 'scripts/vendor_svp_licenses.sh')],
                              env=self.env, capture_output=True, text=True)

    def test_authenticated_and_anonymous_downloads(self):
        for token_name in ('GH_TOKEN', 'GITHUB_TOKEN', None):
            with self.subTest(token_name=token_name):
                shutil.rmtree(self.root / 'app', ignore_errors=True)
                (self.root / 'calls').unlink(missing_ok=True)
                self.env.pop('GH_TOKEN', None)
                self.env.pop('GITHUB_TOKEN', None)
                if token_name:
                    self.env[token_name] = 'test-token'
                result = self.run_bundle()
                self.assertEqual(result.returncode, 0, result.stderr)
                calls = [json.loads(line) for line in (self.root / 'calls').read_text().splitlines()]
                self.assertEqual(len(calls), 2)
                for call in calls:
                    self.assertEqual('Authorization: Bearer test-token' in call, token_name is not None)
                self.assertNotIn('test-token', result.stdout + result.stderr)

    def test_failed_raw_download_is_not_cached(self):
        self.env['FAIL_DOWNLOAD'] = '1'
        result = self.run_bundle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('SVP license download failed: https://raw.githubusercontent.com/', result.stderr)
        dest = self.root / 'app/src/main/assets/licenses/open-svpflow/example-1.0'
        self.assertEqual(list(dest.iterdir()), [])
        self.env.pop('FAIL_DOWNLOAD')
        result = self.run_bundle()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((dest / 'LICENSE').read_text(), 'MIT license')
