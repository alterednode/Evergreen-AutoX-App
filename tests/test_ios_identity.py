"""Portable policy/storage harness; Apple Keychain and UI still need Xcode QA."""

import shutil
import subprocess
from pathlib import Path

import pytest


def test_origin_identity_runtime(tmp_path):
    swift = shutil.which("swiftc")
    if not swift:
        pytest.skip("swiftc unavailable; run IdentityRegression.swift on macOS")
    root = Path(__file__).resolve().parents[1]
    identity = (
        root / "Evergreen AutoX App iOS/Evergreen AutoX App iOS/DeviceIdentity.swift"
    )
    output = tmp_path / "identity-regression"
    subprocess.run(
        [
            swift,
            "-D",
            "AUTOX_IDENTITY_TEST",
            str(identity),
            str(root / "tests/ios/IdentityRegression.swift"),
            "-o",
            str(output),
        ],
        check=True,
        timeout=60,
    )
    subprocess.run([str(output)], check=True, timeout=30)
