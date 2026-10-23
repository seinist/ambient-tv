"""Keep FFmpeg updates on the requested maintenance branch."""
import sys
import subprocess
import tempfile
import shutil
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import update_external_libraries as updater


class FFmpegUpdatesTest(unittest.TestCase):
    @patch.object(updater.subprocess, 'check_output')
    def test_tracks_branch_head_instead_of_latest_release(self, remote):
        sha = 'a' * 40
        remote.return_value = f'{sha}\trefs/heads/release/9.0\n'
        self.assertEqual(updater.ffmpeg_latest(), {
            'FFMPEG_REF': 'release/9.0', 'FFMPEG_COMMIT': sha,
        })
        self.assertEqual(remote.call_args.args[0][-1], 'refs/heads/release/9.0')

    @patch.object(updater.subprocess, 'check_output')
    def test_rejects_wrong_branch_or_invalid_commit(self, remote):
        for reply in ['a' * 40 + '\trefs/heads/master', 'invalid\trefs/heads/release/9.0']:
            with self.subTest(reply=reply):
                remote.return_value = reply
                with self.assertRaises(RuntimeError):
                    updater.ffmpeg_latest()


class FFmpegCacheTest(unittest.TestCase):
    def test_only_matching_snapshot_reuses_local_audio(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / 'scripts').mkdir()
            shutil.copy(Path(__file__).resolve().parents[1] / 'prepare_local_audio.sh', root / 'scripts')
            (root / 'gradle').mkdir()
            (root / 'gradle/external-versions.properties').write_text(
                'FFMPEG_REF=release/9.0\nFFMPEG_COMMIT=' + 'a' * 40 + '\n')
            prefix = root / '.native/mpv-android/buildscripts/prefix/arm64/include'
            for header in ['libavcodec/avcodec.h', 'libavutil/avutil.h',
                           'libavutil/audio_fifo.h', 'libavutil/opt.h', 'libswresample/swresample.h']:
                file = prefix / header
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('fixture')
            libs = root / 'app/src/main/jniLibs/arm64-v8a'
            libs.mkdir(parents=True)
            for lib in ['avcodec', 'avutil', 'swresample']:
                (libs / f'lib{lib}.so').write_text('fixture')
            marker = libs.parent / '.mpv-version'
            for content, expected in [('', 1),
                    ('ffmpeg_ref=release/9.0\nffmpeg_commit=' + 'b' * 40 + '\n', 1),
                    ('ffmpeg_ref=release/9.0\nffmpeg_commit=' + 'a' * 40 + '\n', 0)]:
                with self.subTest(marker=content):
                    marker.write_text(content)
                    import os
                    env = {k: v for k, v in os.environ.items() if k not in ('FFMPEG_REF', 'MPV_BUILD_ROOT')}
                    result = subprocess.run(['bash', str(root / 'scripts/prepare_local_audio.sh'),
                                             '--check', 'arm64-v8a'], env=env)
                    self.assertEqual(result.returncode, expected)


if __name__ == '__main__':
    unittest.main()
