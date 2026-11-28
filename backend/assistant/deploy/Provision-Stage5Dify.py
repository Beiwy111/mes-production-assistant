"""Publish the team's Stage5 apps in the local Dify 1.19.0 Docker workspace."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, build_opener


DEPLOY = Path(__file__).resolve().parent
ROLES = ('KNOWLEDGE', 'ORDER', 'SUPPLY', 'PRODUCTION', 'QUALITY', 'EQUIPMENT', 'AFTERSALES', 'SUP')
CONTAINER = 'mes-dify-local-api-1'
DEFAULT_PROVIDER = 'langgenius/deepseek/deepseek'
DEFAULT_MODEL = 'deepseek-flash'
V2_MODEL = 'mes-qwen14b-v2'
V2_PROVIDER = 'langgenius/openai_api_compatible/openai_api_compatible'


def read_keys(path, keys):
    if not path.is_file():
        raise ValueError(f'Create {path.name} with the local setup command first.')
    values = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        name, sep, value = line.partition('=')
        if sep and name in keys:
            if name in values or not value.strip() or value != value.strip():
                raise ValueError(f'Invalid or duplicate {name} in {path.name}.')
            values[name] = value
    missing = sorted(set(keys) - values.keys())
    if missing:
        raise ValueError(f'Missing {", ".join(missing)} in {path.name}.')
    return values


def restrict_file(path):
    if os.name == 'nt':
        account = subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'])
        sid = re.search(rb'S-1-\d+(?:-\d+)+', account).group().decode('ascii')
        subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', '*'+sid+':F', '*S-1-5-18:F'],
                       check=True, capture_output=True)
    else:
        os.chmod(path, 0o600)


def private_write(path, content):
    if path.is_file() and path.read_bytes() == content.encode('utf-8'):
        restrict_file(path)
        return
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.env.tmp-', delete=False) as fh:
        tmp = Path(fh.name)
    try:
        restrict_file(tmp)
        tmp.write_bytes(content.encode('utf-8'))
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


def read_templates(deploy):
    agents = deploy.parent/'agents'
    templates = [p.relative_to(agents) for p in sorted((agents/'prompts/stage5').glob('*.txt'))]
    templates.extend(map(Path, ['scripts/build_stage5.py', 'scripts/stage5_nodes.py', 'workflows/stage4-graph-rag.yml']))
    files = {p.as_posix(): (agents/p).read_text(encoding='utf-8') for p in templates}
    files['manage.py'] = (deploy/'Manage-Stage5Dify.py').read_text(encoding='utf-8')
    return files


def prepare(provider, model, deploy=DEPLOY):
    role_keys = {r: f'MES_AGENT_{r}_KEY' for r in ROLES if r != 'KNOWLEDGE'}
    agent_keys = read_keys(deploy/'.env.agents.local', role_keys.values())
    secrets = {r: agent_keys[k] for r, k in role_keys.items()}
    secrets['KNOWLEDGE'] = read_keys(deploy/'.env.knowledge.local', ['MES_KNOWLEDGE_API_KEY'])['MES_KNOWLEDGE_API_KEY']
    if len(set(secrets.values())) != len(secrets):
        raise ValueError('Each Stage5 role and the knowledge service need their own credential.')
    bindings = deploy/'.env.stage5-bindings.local'
    tenant = json.loads(bindings.read_text(encoding='utf-8'))['tenant_id'] if bindings.is_file() else None
    return dict(files=read_templates(deploy), secrets=secrets, provider=provider, model=model, expected_tenant=tenant)


def offers_pinned_model(base_url):
    try:
        with build_opener(ProxyHandler({})).open(base_url + '/models', timeout=4) as response:
            models = json.loads(response.read(1_048_576)).get('data', [])
        return any(isinstance(item, dict) and item.get('id') == V2_MODEL for item in models)
    except (OSError, ValueError, AttributeError):
        return False


def provision(provider, model, *, v2_base_url=None, if_online=False):
    request = prepare(provider, model)
    if v2_base_url is not None:
        url = urlsplit(v2_base_url.rstrip('/'))
        if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.path != '/v1' or url.query or url.fragment:
            raise ValueError('Provide the Ubuntu server base URL ending in /v1, without credentials.')
        v2_base_url = url.geturl()
        settings = DEPLOY/'.env.workbench-v2.local'
        if not settings.exists():
            private_write(settings, f'MES_WORKFLOW_V2_BASE_URL={v2_base_url}\n')
        if not offers_pinned_model(v2_base_url):
            message = 'Ubuntu is unavailable or does not advertise mes-qwen14b-v2. Local workflow publication is pending.'
            if if_online:
                print(message)
                return
            raise ValueError(message)
        request.update(provider=V2_PROVIDER, model=V2_MODEL, v2_base_url=v2_base_url)
    # Verify the Compose labels before passing any credentials into a container.
    fmt = '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.Config.Image}}|{{.State.Running}}'
    inspected = subprocess.run(['docker', 'inspect', '-f', fmt, CONTAINER], capture_output=True, timeout=30)
    fields = inspected.stdout.decode('utf-8', errors='replace').strip().split('|')
    if (inspected.returncode or len(fields) != 4 or fields[:2] != ['mes-dify-local', 'api']
            or not fields[2].endswith('dify-api:1.19.0') or fields[3] != 'true'):
        raise ValueError('Start the mes-dify-local Dify 1.19.0 Docker stack and wait for the API.')
    # Source, templates and keys travel through stdin. Docker argv and container files contain no keys.
    runner = ("import json,sys; p=json.load(sys.stdin); ns={}; "
              "exec(compile(p['worker'], '<stage5-provision>', 'exec'), ns); ns['main'](p['request'])")
    packet = dict(worker=(DEPLOY/'stage5_dify_provision.py').read_text(encoding='utf-8'), request=request)
    proc = subprocess.run(['docker', 'exec', '-i', '-w', '/app/api', CONTAINER, 'python', '-c', runner],
                          input=json.dumps(packet).encode('ascii'), capture_output=True, timeout=900)
    lines = proc.stdout.decode('utf-8', errors='replace').splitlines()
    messages = [line.removeprefix('MES_STAGE5_RESULT=') for line in lines if line.startswith('MES_STAGE5_RESULT=')]
    if not messages:
        raise ValueError('Dify initialization failed. Check that the local API and PostgreSQL are healthy.')
    result = json.loads(messages[-1])
    if proc.returncode or 'error' in result:
        raise ValueError(result.get('error', 'Dify provisioning failed.'))
    token = result.pop('api_key')
    if not re.fullmatch(r'app-[A-Za-z0-9]+', token) or set(result['apps']) != set(ROLES):
        raise ValueError('Dify returned an incomplete workspace configuration.')
    if v2_base_url is not None:
        private_write(DEPLOY/'.env.stage5-v2-bindings.local', json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        private_write(DEPLOY/'.env.workbench-v2.local', f'MES_WORKBENCH_V2_DIFY_API_KEY={token}\n'
                      f'MES_WORKFLOW_V2_BASE_URL={request["v2_base_url"]}\n')
    else:
        private_write(DEPLOY/'.env.stage5-bindings.local', json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        private_write(DEPLOY/'.env.workbench.local', f'MES_WORKBENCH_DIFY_API_KEY={token}\n'
                      'MES_WORKBENCH_DIFY_BASE_URL=http://127.0.0.1:18080/v1\n')
    published_model = V2_MODEL if v2_base_url is not None else model
    print(f'Stage5 ready: {published_model}, public knowledge, six expert tools and SUP published.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider', default=DEFAULT_PROVIDER)
    parser.add_argument('--model', default=DEFAULT_MODEL)
    parser.add_argument('--local-v2', action='store_true', help='Publish a separate workflow family pinned to the Ubuntu fine-tune.')
    parser.add_argument('--base-url', default=os.getenv('MES_WORKFLOW_V2_BASE_URL'), help='Ubuntu OpenAI-compatible base URL for --local-v2.')
    parser.add_argument('--if-online', action='store_true', help='Skip pending v2 setup when Ubuntu is offline.')
    args = parser.parse_args()
    try:
        if args.if_online and not args.local_v2:
            parser.error('--if-online requires --local-v2')
        if args.local_v2 and not args.base_url:
            args.base_url = read_keys(DEPLOY/'.env.workbench-v2.local', ['MES_WORKFLOW_V2_BASE_URL'])['MES_WORKFLOW_V2_BASE_URL']
        provision(args.provider, args.model, v2_base_url=args.base_url if args.local_v2 else None, if_online=args.if_online)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        sys.exit(str(error) if isinstance(error, ValueError) else f'Local Dify setup failed ({type(error).__name__}).')
