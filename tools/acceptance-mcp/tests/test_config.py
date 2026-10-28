from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from solo_acceptance_mcp.bridge import (
    BridgeConfig, ConfigurationError, load_config,
)
from tests.helpers import PASSWORD, TOKEN, USERNAME


class ConfigTests(unittest.TestCase):
    def test_config_repr_and_validation_do_not_expose_credentials(self) -> None:
        config = BridgeConfig(username=USERNAME, password=PASSWORD)
        self.assertNotIn(USERNAME, repr(config))
        self.assertNotIn(PASSWORD, repr(config))
        for url in ["file:///secret", "http://user:secret@localhost", "http://localhost/?secret=1", "http://localhost/path", " http://localhost", "http://localhost\n"]:
            with self.subTest(url=url), self.assertRaises(ConfigurationError):
                BridgeConfig(username=USERNAME, password=PASSWORD, base_url=url)
        for timeout in [True, 0, 31, float("inf")]:
            with self.subTest(timeout=timeout), self.assertRaises(ConfigurationError):
                BridgeConfig(username=USERNAME, password=PASSWORD, timeout_seconds=timeout)

    def test_file_defaults_and_malformed_content(self) -> None:
        temporary_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as directory:
            path = Path(directory) / "private.json"
            path.write_text(json.dumps({"username": USERNAME, "password": PASSWORD}))
            config = load_config(path)
            self.assertEqual("http://127.0.0.1:8686", config.base_url)
            self.assertEqual(10, config.timeout_seconds)
            path.write_text(PASSWORD)
            with self.assertRaises(ConfigurationError) as error:
                load_config(path)
            self.assertNotIn(PASSWORD, str(error.exception))
            path.write_text(json.dumps({"username": USERNAME, "password": PASSWORD, "extra": TOKEN}))
            with self.assertRaises(ConfigurationError):
                load_config(path)
