import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from log_sync import sync_file


class LogSyncTests(unittest.TestCase):
    def test_flushes_existing_log_and_tolerates_not_yet_created_recorder(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'session.jsonl'
            path.write_text('record\n')
            with patch('log_sync.os.fsync', wraps=os.fsync) as sync:
                self.assertEqual(sync_file(path), 'synced')
                sync.assert_called_once()
            self.assertEqual(sync_file(Path(directory) / 'later.jsonl'), 'not_created')

    def test_worker_syncs_and_exits_without_rewriting_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'session.jsonl'
            path.write_text('record\n')
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('log_sync.py')),
                                     '--interval', '.05', '--duration', '.1', str(path)],
                                    capture_output=True, text=True, timeout=3)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('Sync ready', result.stdout)
            self.assertIn('"synced"', result.stdout)
            self.assertIn('Sync finished', result.stdout)
            self.assertEqual(path.read_text(), 'record\n')

    def test_refuses_symlinks_and_nonregular_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'target').write_text('record')
            (path / 'link').symlink_to(path / 'target')
            with self.assertRaises(OSError):
                sync_file(path / 'link')
            with self.assertRaises(ValueError):
                sync_file(path)
            self.assertEqual(sync_file(path, directory=True), 'synced')


if __name__ == '__main__':
    unittest.main()
