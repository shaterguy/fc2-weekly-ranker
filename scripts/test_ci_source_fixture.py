import importlib.util
import io
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ci_source_fixture_under_test', Path(__file__).with_name('ci_source_fixture.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class FakeAdb:
    def __init__(self, *, present=False, presence_error=False, bad_state=False, running=False,
                 process_error=False, write_error=False, read_error=False, mismatch=False):
        self.present = present
        self.presence_error = presence_error
        self.bad_state = bad_state
        self.running = running
        self.process_error = process_error
        self.write_error = write_error
        self.read_error = read_error
        self.mismatch = mismatch
        self.storage = b'previous preferences' if present else b''
        self.mutations = []
        self.write_command = None
        self.read_commands = []

    def check_output(self, command, **kwargs):
        if command[-1] == 'get-serialno': return 'emulator-5554\n'
        if command[-1] == 'ro.kernel.qemu': return '1\n'
        if 'exec-out' in command:
            self.read_commands.append(command)
            return b'bad data' if self.mismatch else self.storage
        raise AssertionError('unexpected check_output invocation')

    def run(self, command, **kwargs):
        joined = ' '.join(command)
        text = kwargs.get('text', False)
        def result(code=0, stdout=b'', stderr=b''):
            if text:
                stdout, stderr = stdout.decode(), stderr.decode()
            if kwargs.get('check') and code:
                raise subprocess.CalledProcessError(code, command, stdout, stderr)
            return subprocess.CompletedProcess(command, code, stdout, stderr)
        if 'pidof' in joined:
            if self.process_error: return result(1, stderr=b'pidof: Permission denied')
            return result(0, b'42\n') if self.running else result(1)
        if 'PRESENT' in joined or 'test' in command:
            if self.presence_error: return result(1, stderr=b'run-as: exec failed for test: Permission denied')
            if 'test' in command: return result(0 if self.present else 1)
            if self.bad_state: return result(0, b'unknown state')
            return result(0, b'PRESENT' if self.present else b'ABSENT')
        if 'mkdir' in joined:
            self.mutations.append('mkdir')
            return result()
        if 'exec-in' in command or 'set -C' in joined:
            self.mutations.append('write')
            self.write_command = command
            if self.write_error: return result(0, stderr=b'run-as: fixture write failed')
            self.storage = kwargs['input']
            return result()
        if '/system/bin/cat' in joined:
            self.read_commands.append(command)
            if self.read_error: return result(1, stderr=b'cat: Permission denied')
            return result(0, b'bad data' if self.mismatch else self.storage)
        raise AssertionError('unexpected adb operation')


class SeedFixtureTests(unittest.TestCase):
    def seed(self, fake):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'}), \
             patch.object(fixture.subprocess, 'check_output', side_effect=fake.check_output), \
             patch.object(fixture.subprocess, 'run', side_effect=fake.run), redirect_stdout(io.StringIO()):
            fixture.seed_emulator()

    def test_requires_ci_before_adb(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false'}), patch.object(fixture.subprocess, 'check_output') as call:
            with self.assertRaises(RuntimeError): fixture.emulator_command()
            call.assert_not_called()

    def test_refuses_physical_device(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'}), patch.object(fixture.subprocess, 'check_output', return_value='physical-device\n') as call:
            with self.assertRaises(RuntimeError): fixture.emulator_command()
            self.assertEqual(1, call.call_count)

    def test_refuses_non_qemu_serial(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'}), patch.object(fixture.subprocess, 'check_output', side_effect=['emulator-5554\n', '0\n']):
            with self.assertRaises(RuntimeError): fixture.emulator_command()

    def test_refuses_running_app_before_write(self):
        fake = FakeAdb(running=True)
        with self.assertRaises(RuntimeError): self.seed(fake)
        self.assertEqual([], fake.mutations)

    def test_permission_error_is_not_a_missing_process(self):
        fake = FakeAdb(process_error=True)
        with self.assertRaises(RuntimeError): self.seed(fake)
        self.assertEqual([], fake.mutations)

    def test_refuses_existing_preferences(self):
        fake = FakeAdb(present=True)
        with self.assertRaises(RuntimeError): self.seed(fake)
        self.assertEqual([], fake.mutations)
        self.assertEqual(b'previous preferences', fake.storage)

    def test_permission_exit_one_is_not_missing_preferences(self):
        fake = FakeAdb(presence_error=True)
        with self.assertRaises(RuntimeError): self.seed(fake)
        self.assertEqual([], fake.mutations)

    def test_refuses_unrecognized_presence_marker(self):
        fake = FakeAdb(bad_state=True)
        with self.assertRaises(RuntimeError): self.seed(fake)
        self.assertEqual([], fake.mutations)

    def test_binary_seed_uses_no_pty_and_checked_shell_protocol(self):
        fake = FakeAdb()
        self.seed(fake)
        self.assertEqual(fixture.seed_bytes(), fake.storage)
        self.assertEqual(['mkdir', 'write'], fake.mutations)
        self.assertIn('-T', fake.write_command)
        self.assertNotIn('exec-in', fake.write_command)
        remote = shlex.split(fake.write_command[-1])
        self.assertEqual(['run-as', fixture.PACKAGE, '/system/bin/sh', '-c'], remote[:4])
        self.assertIn('set -C', remote[4])
        self.assertTrue(all('-T' in command and 'exec-out' not in command for command in fake.read_commands))

    def test_transfer_failures_and_mismatches_do_not_pass(self):
        for failure in ['write_error', 'read_error', 'mismatch']:
            with self.subTest(failure=failure):
                fake = FakeAdb(**{failure: True})
                with self.assertRaises(RuntimeError): self.seed(fake)
                self.assertEqual(1, fake.mutations.count('write'))

    def test_host_pipe_is_byte_exact_and_noclobber_refuses_overwrite(self):
        script = getattr(fixture, 'WRITE_SCRIPT', 'cat > "$1"')
        build = Path(__file__).resolve().parents[1] / 'build'
        build.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='ci-fixture-binary-', dir=build) as temporary:
            output = Path(temporary) / 'preferences with spaces.pb'
            data = bytes(range(256)) + b'\r\n\x00\n'
            command = [shutil.which('sh'), '-c', script, 'fixture-write', str(output)]
            first = subprocess.run(command, input=data, capture_output=True)
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(data, output.read_bytes())
            second = subprocess.run(command, input=b'replacement', capture_output=True)
            self.assertNotEqual(0, second.returncode)
            self.assertEqual(data, output.read_bytes())

    def test_presence_script_distinguishes_absence_and_existing_file(self):
        script = getattr(fixture, 'FILE_STATE_SCRIPT', 'test -e "$1"')
        build = Path(__file__).resolve().parents[1] / 'build'
        build.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='ci-fixture-state-', dir=build) as temporary:
            output = Path(temporary) / 'preferences with spaces.pb'
            command = [shutil.which('sh'), '-c', script, 'fixture-state', str(output)]
            absent = subprocess.run(command, capture_output=True)
            self.assertEqual((0, b'ABSENT', b''), (absent.returncode, absent.stdout, absent.stderr))
            output.write_bytes(b'existing')
            present = subprocess.run(command, capture_output=True)
            self.assertEqual((0, b'PRESENT', b''), (present.returncode, present.stdout, present.stderr))
