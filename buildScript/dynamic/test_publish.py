import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import publish


class PublishTest(unittest.TestCase):
    def setUp(self):
        # Synthetic test artifacts are retained; no file deletion is needed.
        self.output = Path(tempfile.mkdtemp(prefix='dynamic-publish-test-'))
        contract = json.loads((publish.ROOT / 'buildScript/dynamic/compatibility.json').read_text())
        for abi in ['arm64-v8a', 'x86_64']:
            payload = b'synthetic test payload'
            manifest = dict(version='1.14.5-dynamic.1', abi=abi, binding_sha256=contract['binding_sha256'],
                            sha256=hashlib.sha256(payload).hexdigest())
            with zipfile.ZipFile(self.output / f'nekobox-core-{abi}.zip', 'w') as archive:
                archive.writestr('core.json', json.dumps(manifest))
                archive.writestr('libgojni.so', payload)
        self.argv = ['publish.py', '--version', '1.14.5', '--commit', 'c' * 40, '--source', 'a' * 40,
                     '--run-url', 'https://github.com/example/example/actions/runs/1', '--directory', str(self.output)]

    def invoke(self, dry=False):
        with patch.object(sys, 'argv', self.argv + (['--dry-run'] if dry else [])):
            publish.main()

    @patch('publish.subprocess.run')
    @patch('publish.gh_json')
    def test_dry_run_never_writes_to_github(self, api, command):
        self.invoke(dry=True)
        api.assert_not_called()
        command.assert_not_called()
        self.assertTrue((self.output / 'upstream.json').is_file())

    @patch('publish.subprocess.run')
    @patch('publish.official_commit', return_value='c' * 40)
    @patch('publish.gh_json')
    def test_failed_upload_never_exposes_release(self, api, commit, command):
        api.side_effect = [dict(tag_name='v1.14.5'), [[]]]
        command.side_effect = subprocess.CalledProcessError(1, 'synthetic upload failure')
        with self.assertRaises(subprocess.CalledProcessError):
            self.invoke()
        self.assertEqual(1, command.call_count)
        self.assertIn('--draft', command.call_args.args[0])

    @patch('publish.subprocess.run')
    @patch('publish.official_commit', return_value='c' * 40)
    @patch('publish.gh_json')
    def test_publication_follows_complete_draft_upload(self, api, commit, command):
        api.side_effect = [dict(tag_name='v1.14.5'), [[]]]
        self.invoke()
        self.assertEqual(2, command.call_count)
        self.assertIn('--draft', command.call_args_list[0].args[0])
        self.assertIn('--draft=false', command.call_args_list[1].args[0])

    @patch('publish.subprocess.run')
    @patch('publish.official_commit', return_value='b' * 40)
    @patch('publish.gh_json')
    def test_moved_upstream_tag_stops_publication(self, api, commit, command):
        api.side_effect = [dict(tag_name='v1.14.5'), [[]]]
        with self.assertRaises(ValueError):
            self.invoke()
        command.assert_not_called()

    @patch('publish.subprocess.run')
    @patch('publish.official_commit', return_value='c' * 40)
    @patch('publish.gh_json')
    def test_existing_draft_is_not_overwritten(self, api, commit, command):
        api.side_effect = [dict(tag_name='v1.14.5'), [[dict(tag_name='dynamic-core-1.14.5-auto.1', draft=True)]]]
        with self.assertRaises(ValueError):
            self.invoke()
        command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
