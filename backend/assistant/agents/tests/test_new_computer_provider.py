"""First installation, credential reuse, and loopback checks without paid provider calls."""
import json
from pathlib import Path
import runpy
import subprocess

import pytest


SCRIPT = Path(__file__).resolve().parents[2] / "deploy/Configure-DifyProvider.py"


@pytest.fixture
def provider():
    return runpy.run_path(str(SCRIPT))["main"].__globals__


def test_registration_prompts_once_on_a_new_workspace(provider, monkeypatch):
    states, posts = ["not_started", "finished"], []

    def setup(payload=None):
        if payload is None:
            return {"step": states.pop(0)}
        posts.append(payload)
        return {"result": "success"}

    monkeypatch.setitem(provider, "setup_call", setup)
    monkeypatch.setattr("builtins.input", lambda _: "tester@example.invalid")
    monkeypatch.setattr(provider["getpass"], "getpass", lambda _: "local-test-pass1")
    provider["register_if_new"](False)
    provider["register_if_new"](False)
    assert len(posts) == 1 and posts[0]["email"] == "tester@example.invalid"


def test_provider_secret_only_in_stdin(provider, monkeypatch):
    secret = "provider-test-value"

    def run(argv, **opts):
        assert all(secret not in x for x in argv)
        assert json.loads(opts["input"].splitlines()[1])["key"] == secret
        return subprocess.CompletedProcess(argv, 0, "MES_SETUP_RESULT=ready\n", "")

    monkeypatch.setattr(subprocess, "run", run)
    assert provider["configure"](False, "deepseek-flash", secret) == "ready"


def test_foreign_binding_is_rejected_before_registration(provider, monkeypatch):
    calls = []

    def run(argv, **_):
        calls.append(argv)
        if argv[1] == "port":
            return subprocess.CompletedProcess(argv, 0, "0.0.0.0:18080\n", "")
        role = "api" if argv[-1].endswith("-api-1") else "nginx"
        image = "langgenius/dify-api:1.19.0" if role == "api" else "nginx:latest"
        return subprocess.CompletedProcess(argv, 0, f"mes-dify-local {role} {image} true\n", "")

    monkeypatch.setattr(subprocess, "run", run)
    with pytest.raises(provider["SetupError"], match="127.0.0.1"):
        provider["verify_local_dify"]()
    assert all(cmd[1] in {"inspect", "port"} for cmd in calls)
