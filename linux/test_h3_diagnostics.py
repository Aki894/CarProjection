import tempfile
import unittest
from pathlib import Path

from h3_diagnostics import process_waits


class DiagnosticsTests(unittest.TestCase):
    def test_waits_capture_usb_threads_without_other_process_data(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            for pid, name in ((10, 'python3'), (20, 'other')):
                process = proc / str(pid)
                process.mkdir()
                (process / 'comm').write_text(name + '\n')
                task = process / 'task' / str(pid)
                task.mkdir(parents=True)
                (task / 'status').write_text('State:\tD (disk sleep)\n')
                (task / 'wchan').write_text('wait_for_completion')
                (task / 'stack').write_text('ffs_epfile_io\n' * 1000)
            result = process_waits(proc)
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]['pid'], 10)
            self.assertEqual(result[0]['wchan'], 'wait_for_completion')
            self.assertEqual(len(result[0]['stack']), 4096)


if __name__ == '__main__':
    unittest.main()
