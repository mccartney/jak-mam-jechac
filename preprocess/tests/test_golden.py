# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Grzegorz Olędzki
"""Golden test: build.py on the tiny synthetic feed in fixtures/feed must produce exactly
expected/. The app's unit tests parse the same expected/ files, so together they pin the
JSON contract between the two sides. After an intended format change, regenerate with
UPDATE_GOLDEN=1 python3 -m unittest discover preprocess/tests
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, "..", "build.py")
FEED = os.path.join(HERE, "fixtures", "feed")
EXPECTED = os.path.join(HERE, "expected")


def load(path):
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    data.pop("generated", None)  # wall-clock timestamp
    return data


def json_files(root):
    return sorted(os.path.relpath(os.path.join(d, f), root)
                  for d, _, files in os.walk(root) for f in files if f.endswith(".json"))


class GoldenTest(unittest.TestCase):
    def test_output_matches_expected(self):
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([sys.executable, BUILD, "--feed", FEED, "--out", out],
                           check=True, capture_output=True)
            if os.environ.get("UPDATE_GOLDEN"):
                for rel in json_files(out):
                    os.makedirs(os.path.dirname(os.path.join(EXPECTED, rel)), exist_ok=True)
                    with open(os.path.join(EXPECTED, rel), "w", encoding="utf-8") as fh:
                        json.dump(load(os.path.join(out, rel)), fh, ensure_ascii=False, indent=2)
                        fh.write("\n")
            self.assertEqual(json_files(EXPECTED), json_files(out))
            for rel in json_files(out):
                with self.subTest(file=rel):
                    self.assertEqual(load(os.path.join(EXPECTED, rel)), load(os.path.join(out, rel)))


if __name__ == "__main__":
    unittest.main()
