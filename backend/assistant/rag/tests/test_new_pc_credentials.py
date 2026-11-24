import importlib
import os
from pathlib import Path
import shutil
import subprocess
from unittest.mock import MagicMock

import pytest


DEPLOY = Path(__file__).resolve().parents[2] / 'deploy'


@pytest.fixture
def setup(monkeypatch):
    monkeypatch.syspath_prepend(str(DEPLOY))
    return importlib.import_module('Initialize-MesAssistant')


def test_provision_preserves_credentials_and_never_persists_admin_password(setup, monkeypatch, tmp_path):
    monkeypatch.setattr(setup, 'DEPLOY', tmp_path)
    for name, value in {'MES_SETUP_DB_SCHEMA': 'demo', 'MES_SETUP_DB_PORT': '3311', 'MES_SETUP_DB_USER': 'root',
                        'MES_SETUP_DB_PASSWORD': 'test-database-password', setup.ALLOWLIST: '30, 40,30',
                        setup.MODEL_KEY: 'test-model-key'}.items():
        monkeypatch.setenv(name, value)
    cursor = MagicMock()
    columns = {(t, col) for profile in setup.readers.PROFILES.values() for t, cols in profile.items()
               for col in (cols or 'test_column').split()}
    columns.update(tuple(name.split('.')) for name in setup.seed.COLUMNS)
    cursor.fetchall.return_value = columns
    db = MagicMock()
    db.__enter__.return_value.cursor.return_value.__enter__.return_value = cursor
    monkeypatch.setattr(setup.readers, 'connect', lambda config: db)
    prepare = MagicMock()
    monkeypatch.setattr(setup.readers, 'prepare', prepare)
    draft = MagicMock()
    monkeypatch.setattr(setup.drafts, 'main', draft)

    setup.main()
    role_file = tmp_path / '.env.agents.local'
    daily_file = tmp_path / '.env.daily-chat.local'
    snapshot = role_file.read_bytes(), daily_file.read_bytes()
    roles = setup.readers.values(role_file)
    setup.check_role_keys(roles)
    assert roles[setup.ALLOWLIST] == '30,40'
    assert roles['MES_AGENT_JDBC_URL'].startswith('jdbc:mysql://127.0.0.1:3311/demo?')
    assert set(roles).isdisjoint({'MES_AGENT_JDBC_USER', 'MES_AGENT_JDBC_PASSWORD', setup.MODEL_KEY})
    assert b'test-database-password' not in b''.join(snapshot)
    assert setup.readers.values(daily_file) == {setup.MODEL_KEY: 'test-model-key'}
    assert [call.args[0] for call in prepare.call_args_list] == list(setup.readers.PROFILES)
    assert draft.call_args.args[0]['MES_AGENT_JDBC_PASSWORD'] == 'test-database-password'
    monkeypatch.setenv(setup.MODEL_KEY, 'another-test-key')
    setup.main()
    assert (role_file.read_bytes(), daily_file.read_bytes()) == snapshot


@pytest.mark.parametrize('invalid', ['*', '1,0', '9223372036854775808', '1,,2', '3\nMES_AGENT_PORT=8'])
def test_preview_rejects_invalid_order_scope(setup, invalid):
    with pytest.raises(setup.SetupError):
        setup.allowed_orders(invalid)


def test_schema_reports_missing_business_tables(setup):
    cursor = MagicMock()
    cursor.fetchall.return_value = [('customer_order', 'order_id')]
    with pytest.raises(setup.SetupError, match='production_plan_schedule'):
        setup.schema_check(cursor)


def test_private_file_commit_is_create_only(setup, tmp_path):
    dest = tmp_path / '.env.test.local'
    setup.readers.write_private(dest, 'first')
    with pytest.raises(FileExistsError):
        setup.readers.write_private(dest, 'second')
    assert dest.read_text() == 'first' and list(tmp_path.iterdir()) == [dest]


@pytest.mark.skipif(os.name != 'nt', reason='Windows PowerShell launcher')
def test_workbench_reloads_private_key_after_restart(setup, tmp_path):
    deploy = tmp_path / 'backend/assistant/deploy'
    deploy.mkdir(parents=True)
    for name in ('Start-WorkbenchLocal.ps1', 'AgentRuntime.Common.ps1'):
        shutil.copyfile(DEPLOY / name, deploy / name)
    (deploy / '.env.agents.local').write_text(''.join(f'{k}=test-{k}\n' for k in setup.ROLE_KEYS)
                                               + 'MES_AGENT_ALLOWED_ORDER_IDS=1\n')
    (deploy / '.env.workbench-read.local').write_text(
        'MES_AGENT_JDBC_URL=jdbc:mysql://127.0.0.1:3306/demo?serverTimezone=UTC\n'
        'MES_AGENT_JDBC_USER=mes_asst_ro_workbench\nMES_AGENT_JDBC_PASSWORD=test-password\n')
    (deploy / '.env.daily-chat.local').write_text('DASHSCOPE_API_KEY=test-private-gateway-key\n')
    data = deploy.parent / 'rag/data'
    main = data / 'runtime-build/classes/com/upc/computer/assistant/v2/runtime/WorkbenchRuntimeMain.class'
    main.parent.mkdir(parents=True)
    main.touch()
    (data / 'stage5-classpath.txt').write_text('test-classpath')
    script = tmp_path / 'launch-test.ps1'
    script.write_text("""
$ErrorActionPreference = 'Stop'
function Get-NetTCPConnection {}
function Start-Process {
    if ($env:DASHSCOPE_API_KEY -cne 'test-private-gateway-key') { throw 'Missing daily key' }
    if ($env:MES_AGENT_JDBC_USER -cne 'mes_asst_ro_workbench') { throw 'Wrong reader' }
    if ($env:MES_AGENT_ORDER_KEY) { throw 'Unexpected role key' }
    return [PSCustomObject]@{Id=12345}
}
& ./backend/assistant/deploy/Start-WorkbenchLocal.ps1
if ($env:DASHSCOPE_API_KEY) { throw 'Gateway key escaped into parent environment' }
""")
    env = {k: v for k, v in os.environ.items() if k not in ('DASHSCOPE_API_KEY', 'MES_AGENT_ORDER_KEY')}
    proc = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                           '-File', str(script)], cwd=tmp_path, env=env, capture_output=True, timeout=30)
    assert proc.returncode == 0, proc.stderr.decode(errors='replace')
    assert b'test-private-gateway-key' not in proc.stdout + proc.stderr


@pytest.mark.skipif(os.name != 'nt', reason='Windows PowerShell installer')
def test_dify_uses_local_appdata_then_discovers_existing_checkout(tmp_path):
    for name in ('Prepare-DifyLocal.ps1', 'dify-local.compose.yml'):
        shutil.copyfile(DEPLOY / name, tmp_path / name)
    script = tmp_path / 'dify-test.ps1'
    script.write_text("""
$ErrorActionPreference = 'Stop'
Import-Module Microsoft.PowerShell.Utility
$env:LOCALAPPDATA = Join-Path $PSScriptRoot 'User Data'
$checkout = Join-Path $env:LOCALAPPDATA 'MES-Assistant/dify-1.19.0'
$working = Join-Path $checkout 'docker'
$installed = $false
function git {
    $global:LASTEXITCODE = 0
    if ($args -contains 'clone') {
        New-Item -ItemType Directory -Path (Join-Path $checkout '.git'),$working -Force | Out-Null
    } elseif ($args -contains 'get-url') { 'https://github.com/langgenius/dify.git' }
    elseif ($args -contains 'describe') { '1.19.0' }
}
function docker {
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'ps' -and $installed) { 'test-id' }
    elseif ($args[0] -eq 'inspect') { @{ 'com.docker.compose.project.working_dir' = $working } | ConvertTo-Json -Compress }
    elseif ($args -contains 'up') { throw 'Unexpected Docker mutation' }
}
& ./Prepare-DifyLocal.ps1
$envFile = Join-Path $working '.env'
$first = [IO.File]::ReadAllText($envFile)
$installed = $true
$env:LOCALAPPDATA = Join-Path $PSScriptRoot 'Another Folder'
& ./Prepare-DifyLocal.ps1
if ([IO.File]::ReadAllText($envFile) -cne $first) { throw 'Dify credentials changed' }
if (Test-Path $env:LOCALAPPDATA) { throw 'New checkout created instead of reusing the installed project' }
""")
    proc = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                           '-File', str(script)], cwd=tmp_path, capture_output=True, timeout=40)
    assert proc.returncode == 0, proc.stderr.decode(errors='replace')
