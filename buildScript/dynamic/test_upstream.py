import unittest
from unittest.mock import patch
from upstream import select, official_commit


def release(v, draft=False, assets=True):
    return dict(tag_name=f'dynamic-core-{v}-preview.1', draft=draft,
                assets=[dict(name='nekobox-core-arm64-v8a.zip')] if assets else [])


class UpstreamTest(unittest.TestCase):
    def decision(self, v='1.14.5', releases=None, verify=False):
        return select(dict(tag_name='v' + v, prerelease=False, draft=False),
                      releases or [], '1.14.4', verify)

    def test_new_stable_publishes(self):
        self.assertTrue(self.decision()['publish'])

    def test_same_version_skips_build(self):
        self.assertFalse(self.decision('1.14.4')['build'])

    def test_already_published_skips(self):
        self.assertFalse(self.decision(releases=[release('1.14.5')])['build'])

    def test_prevents_downgrade(self):
        self.assertFalse(self.decision(releases=[release('1.15.0')])['publish'])

    def test_numeric_version_order(self):
        self.assertTrue(self.decision('1.14.10', [release('1.14.9')])['publish'])

    def test_dry_run_builds_without_publishing(self):
        result = self.decision(verify=True)
        self.assertTrue(result['build'])
        self.assertFalse(result['publish'])

    def test_draft_or_missing_package_does_not_hide_update(self):
        self.assertTrue(self.decision(releases=[release('1.14.5', draft=True), release('1.14.5', assets=False)])['publish'])

    def test_rejects_alpha_and_shell_syntax(self):
        for v in ['1.15.0-alpha.1', '1.14.5;echo bad', '../main']:
            with self.assertRaises(ValueError):
                self.decision(v)
        with self.assertRaises(ValueError):
            select(dict(tag_name='v1.14.5', prerelease=True), [], '1.14.4')

    @patch('upstream.gh_json')
    def test_annotated_tag_is_resolved(self, api):
        api.side_effect = [dict(object=dict(type='tag', sha='b' * 40)), dict(object=dict(type='commit', sha='a' * 40))]
        self.assertEqual('a' * 40, official_commit('v1.14.5'))


if __name__ == '__main__':
    unittest.main()
