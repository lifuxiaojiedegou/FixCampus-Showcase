#!/usr/bin/env python3
"""Exercise the workflow's real report collector using synthetic, isolated fixtures."""

import ast
from contextlib import chdir, redirect_stdout
import io
import json
from pathlib import Path
import re
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


PROJECT_ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = PROJECT_ROOT / '.github' / 'workflows' / 'ci.yml'
SYNTHETIC_SECRET = 'synthetic-db-password-0123456789'
SYNTHETIC_TOKEN = 'synthetic-token-0123456789'


def collector_code():
    matches = list(re.finditer(
        r"^(?P<indent> +)python3 - <<'PY'\r?\n(?P<code>[\s\S]*?)^(?P=indent)PY[ \t]*$",
        WORKFLOW.read_text(encoding='utf-8'), re.MULTILINE))
    if len(matches) != 1:
        raise AssertionError('Expected exactly one inline Python report collector.')
    code = textwrap.dedent(matches[0].group('code'))
    ast.parse(code)
    return compile(code, str(WORKFLOW) + ':report-collector', 'exec')


class ArtifactRedactionRegressionTest(unittest.TestCase):
    def setUp(self):
        # Fixtures stay inside this checkout, never in the real target/ or a database.
        self.temporary = tempfile.TemporaryDirectory(
            prefix='.ci-artifact-regression-', dir=PROJECT_ROOT)
        self.root = Path(self.temporary.name).resolve()
        self.assertEqual(self.root.parent, PROJECT_ROOT)
        self.addCleanup(self.clean_temporary_fixture)
        self.source = self.root / 'target' / 'surefire-reports'
        self.source.mkdir(parents=True)
        self.evidence = self.root / 'runtime' / 'fixcampus-ci-evidence'
        self.uploaded = self.evidence / 'surefire-reports'
        self.parsed_paths = []
        self.prepare_synthetic_coverage()

    def clean_temporary_fixture(self):
        # Verify the exact cleanup boundary before TemporaryDirectory removes it.
        if (self.root.parent != PROJECT_ROOT
                or not self.root.name.startswith('.ci-artifact-regression-')):
            raise AssertionError('Refusing cleanup outside the isolated fixture.')
        self.temporary.cleanup()

    def prepare_synthetic_coverage(self):
        """Satisfy unrelated coverage checks; this is not measured Java coverage."""
        coverage = self.root / 'target' / 'site' / 'jacoco'
        (coverage / 'jacoco-resources').mkdir(parents=True)
        (coverage / 'index.html').write_text('<html><body>Fixture</body></html>', encoding='utf-8')
        (coverage / 'jacoco-resources' / 'report.css').write_text('body {}', encoding='utf-8')
        (coverage / 'jacoco.xml').write_text(
            '<report name="synthetic-fixture"><sessioninfo id="fixture"/>'
            '<package name="fixture"><class name="Synthetic">'
            '<counter type="INSTRUCTION" missed="0" covered="1"/>'
            '</class></package><counter type="LINE" missed="0" covered="1"/>'
            '<counter type="BRANCH" missed="0" covered="1"/>'
            '<counter type="INSTRUCTION" missed="0" covered="1"/></report>', encoding='utf-8')
        (coverage / 'jacoco.csv').write_text(
            'GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,'
            'BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED\n'
            'fixture,fixture,Synthetic,0,1,0,1,0,1\n', encoding='utf-8')
        (self.root / 'target' / 'jacoco.exec').write_bytes(b'synthetic-fixture-not-real-coverage')

    def report(self, name='TEST-example.xml', content='', tests=4, failures=0, errors=0, skipped=0):
        path = self.source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">'
            + content + '</testsuite>', encoding='utf-8')
        return path

    def summary(self):
        return json.loads((self.evidence / 'test-summary.json').read_text(encoding='utf-8'))

    def run_collector(self, outcome='success', before_parse=None):
        environment = {
            'RUNNER_TEMP': str(self.root / 'runtime'),
            'VERIFY_OUTCOME': outcome,
            'GITHUB_REPOSITORY': 'example/fixcampus',
            'GITHUB_REPOSITORY_ID': '42',
            'GITHUB_RUN_ID': '42',
            'GITHUB_RUN_ATTEMPT': '1',
            'PUSH_BEFORE_SHA': '1' * 40,
            'GITHUB_EVENT_NAME': 'push',
            'GITHUB_REF': 'refs/heads/main',
            'GITHUB_SHA': '3' * 40,
            'GITHUB_STEP_SUMMARY': str(self.root / 'step-summary.md'),
            'FIXCAMPUS_DB_PASSWORD': SYNTHETIC_SECRET,
            'EXAMPLE_TOKEN': SYNTHETIC_TOKEN,
        }
        real_parse = ET.parse

        def observe_parse(path, *args, **kwargs):
            resolved = Path(path).resolve()
            self.parsed_paths.append(resolved)
            if before_parse:
                before_parse(resolved)
            return real_parse(path, *args, **kwargs)

        # Clear inherited environment: no real credential enters any fixture.
        with chdir(self.root), patch.dict('os.environ', environment, clear=True), \
                patch.object(ET, 'parse', side_effect=observe_parse), redirect_stdout(io.StringIO()):
            exec(collector_code(), {'__name__': '__ci_report_collector__'})
        return self.summary()

    def test_main_push_provenance_uses_actual_commit(self):
        self.report(tests=2)
        self.run_collector()
        identity = json.loads((self.evidence / 'evidence.json').read_text(encoding='utf-8'))
        self.assertEqual(identity['eventName'], 'push')
        self.assertEqual(identity['ref'], 'refs/heads/main')
        self.assertEqual(identity['headSha'], '3' * 40)
        self.assertEqual(identity['baseSha'], '1' * 40)
        self.assertEqual(identity['testedMergeSha'], '3' * 40)

    def test_attribute_secrets_remain_valid_xml(self):
        self.report(content=(
            '<properties><property name="password" value="' + SYNTHETIC_SECRET + '"/>'
            '<property name="token" value="' + SYNTHETIC_TOKEN + '"/></properties>'))
        self.run_collector()
        target = self.uploaded / 'TEST-example.xml'
        properties = ET.parse(target).getroot().findall('./properties/property')
        self.assertEqual([item.attrib['value'] for item in properties], ['[REDACTED]'] * 2)
        self.assertNotIn(SYNTHETIC_SECRET, target.read_text(encoding='utf-8'))
        self.assertNotIn(SYNTHETIC_TOKEN, target.read_text(encoding='utf-8'))

    def test_text_node_secrets_remain_valid_xml(self):
        self.report(content='<system-out>before ' + SYNTHETIC_SECRET + ' after</system-out>')
        self.run_collector()
        self.assertEqual(ET.parse(self.uploaded / 'TEST-example.xml').getroot().findtext('system-out'),
                         'before [REDACTED] after')

    def test_report_without_secrets_preserves_content(self):
        source = self.report(content='<system-out>safe &amp; unchanged</system-out>')
        self.run_collector()
        self.assertEqual(source.read_bytes(), (self.uploaded / source.name).read_bytes())

    def test_every_uploaded_xml_is_parsed_including_nested_reports(self):
        self.report(tests=2)
        self.report('nested/TEST-second.xml', tests=3)
        result = self.run_collector()
        parsed_tests = [p for p in self.parsed_paths if p.name.startswith('TEST-')]
        self.assertEqual(set(parsed_tests), {
            self.uploaded / 'TEST-example.xml', self.uploaded / 'nested' / 'TEST-second.xml'})
        self.assertTrue(all(p.is_relative_to(self.uploaded) for p in parsed_tests))
        self.assertEqual(result['reportFiles'], 2)
        self.assertEqual(result['totals']['tests'], 5)

    def test_statistics_use_uploaded_copy_for_all_four_counters(self):
        source = self.report('TEST-counts.xml', tests=37)

        def change_uploaded_counts(path):
            if path == self.uploaded / source.name:
                path.write_text('<testsuite tests="9" failures="2" errors="1" skipped="3"/>', encoding='utf-8')

        result = self.run_collector(outcome='failure', before_parse=change_uploaded_counts)
        self.assertEqual(result['totals'], dict(tests=9, failures=2, errors=1, skipped=3))
        self.assertEqual(ET.parse(source).getroot().attrib['tests'], '37')

    def test_malformed_uploaded_xml_blocks_successful_maven_acceptance(self):
        source = self.report('TEST-broken.xml', tests=2)

        def damage_uploaded_copy(path):
            if path == self.uploaded / source.name:
                path.write_text('<testsuite><broken></testsuite>', encoding='utf-8')

        with self.assertRaisesRegex(SystemExit, 'passing Surefire reports'):
            self.run_collector(before_parse=damage_uploaded_copy)
        self.assertEqual(self.summary()['parseErrors'], ['TEST-broken.xml'])
        self.assertEqual(ET.parse(source).getroot().attrib['tests'], '2')

    def test_missing_reports_block_successful_maven_acceptance(self):
        with self.assertRaisesRegex(SystemExit, 'passing Surefire reports'):
            self.run_collector()
        self.assertEqual(self.summary()['reportFiles'], 0)

    def test_existing_maven_failure_is_preserved_in_summary(self):
        self.report(tests=3, failures=1, errors=1)
        result = self.run_collector(outcome='failure')
        self.assertEqual(result['verifyOutcome'], 'failure')
        self.assertEqual(result['totals'], dict(tests=3, failures=1, errors=1, skipped=0))


if __name__ == '__main__':
    unittest.main(verbosity=2)
