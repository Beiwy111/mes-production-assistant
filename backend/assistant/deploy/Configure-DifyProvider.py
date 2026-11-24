"""First-run setup for the pinned local Dify distribution. Secrets only travel over stdin or loopback."""
import argparse
import getpass
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEEPSEEK = "langgenius/deepseek/deepseek"
PLUGINS = {
    "langgenius/agent": "0.0.51@3c6c32e593b33476cb52f4e5aaf1bf23e1c71b3d07ba64d40690e0dbce3a7e34",
    "langgenius/deepseek": "0.0.28@771ac435ac1b16e8b30802aa99b226668f591cad7c5cf36cf42eb559a86a008c",
}
API_CONTAINER = "mes-dify-local-api-1"
SETUP = "http://127.0.0.1:18080/console/api/setup"


class SetupError(Exception):
    pass


def in_workspace(request):
    # Uses the same install and validated credential services as Studio in Dify 1.19.0.
    from gevent import monkey
    monkey.patch_all()
    sys.path.insert(0, "/app/api")
    from app_factory import create_app
    from core.plugin.plugin_service import PluginService
    from extensions.ext_application_services import application_services
    from extensions.ext_database import db
    from models.account import Account, Tenant, TenantAccountJoin
    from services.model_provider.service import ModelProviderService
    from sqlalchemy import select

    _, app = create_app()
    with app.app_context(), app.test_request_context("/"):
        tenants = db.session.scalars(select(Tenant).limit(2)).all()
        if len(tenants) != 1:
            return "single_workspace_required"
        tenant = tenants[0].id
        existing = {plugin.plugin_id for plugin in PluginService.list(tenant)}
        missing = [f"{name}:{version}" for name, version in PLUGINS.items() if name not in existing]
        if missing:
            if request["check"]:
                return "plugins_missing"
            PluginService.install_from_marketplace_pkg(tenant, missing)
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                time.sleep(3)
                if set(PLUGINS) <= {p.plugin_id for p in PluginService.list(tenant)}:
                    break
            else:
                return "plugins_timeout"
        service = ModelProviderService()
        def ready():
            return any(m.model == request["model"] and m.status == "active"
                       for m in service.list_models(tenant, provider=DEEPSEEK))

        if ready():
            return "ready"
        credentials = service.get_provider_available_credentials(
            tenant, DEEPSEEK, credential_query=application_services().credential_queries)
        if credentials:
            return "configured_model_unavailable"
        if request["check"] or not request.get("key"):
            return "needs_deepseek_key"
        owner = db.session.scalar(select(Account).join(TenantAccountJoin, TenantAccountJoin.account_id == Account.id)
                                  .where(TenantAccountJoin.tenant_id == tenant, TenantAccountJoin.role == "owner"))
        if owner is None:
            return "single_workspace_required"
        owner.set_tenant_id_with_session(tenant, session=db.session)
        from flask import g
        g._login_user = owner
        credential_id = service.create_provider_credential(tenant, DEEPSEEK, {"api_key": request["key"]}, "MES DeepSeek")
        service.switch_active_provider_credential(tenant, DEEPSEEK, credential_id)
        return "ready" if ready() else "configured_model_unavailable"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def verify_local_dify():
    expected = {"api": "langgenius/dify-api:1.19.0", "nginx": None}
    template = '{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.service"}} {{.Config.Image}} {{.State.Running}}'
    for role, image in expected.items():
        name = f"mes-dify-local-{role}-1"
        result = subprocess.run(["docker", "inspect", "--format", template, name],
                                capture_output=True, text=True, timeout=15)
        values = result.stdout.strip().split()
        if (result.returncode or len(values) != 4 or values[:2] != ["mes-dify-local", role]
                or values[3] != "true" or (image and values[2] != image)):
            raise SetupError(f"Expected the project-owned running Dify {role} container.")
    binding = subprocess.run(["docker", "port", "mes-dify-local-nginx-1", "80/tcp"],
                             capture_output=True, text=True, timeout=15)
    if binding.returncode or binding.stdout.strip() != "127.0.0.1:18080":
        raise SetupError("Expected the local Dify setup endpoint on 127.0.0.1:18080.")


def setup_call(payload=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    req = urllib.request.Request(SETUP, data=json.dumps(payload).encode() if payload else None,
                                 headers={"Content-Type": "application/json"})
    with opener.open(req, timeout=8 if payload is None else 30) as response:
        return json.load(response)


def register_if_new(check):
    deadline = time.monotonic() + 120
    while True:
        try:
            step = setup_call()["step"]
            break
        except (OSError, ValueError, KeyError):
            if time.monotonic() >= deadline:
                raise SetupError("Dify setup endpoint unavailable on 18080.")
            time.sleep(2)
    if step == "finished":
        return
    if check or step != "not_started":
        raise SetupError("Initialize the local Dify workspace first.")
    email = input("Email for YOUR new local Dify administrator: ").strip()
    password = getpass.getpass("New Dify password (at least 8 characters, letters and digits): ")
    if password != getpass.getpass("Repeat the new Dify password: "):
        raise SetupError("Passwords differ. Re-run setup.")
    try:
        result = setup_call({"email": email, "name": "MES Admin", "password": password, "language": "zh-Hans"})
    except (OSError, ValueError):
        raise SetupError("Dify rejected local registration. Check the email/password or use http://127.0.0.1:18080/install and re-run.")
    if result.get("result") != "success":
        raise SetupError("Dify setup incomplete. Complete local registration and re-run.")


def configure(check, model, key=None):
    loader = "import json,sys;exec(compile(json.loads(sys.stdin.readline()),'mes-provider-setup','exec'))"
    source = Path(__file__).read_text(encoding="utf-8-sig")
    # Do not pass a provider key through process arguments, command history or the repository.
    data = json.dumps(source) + "\n" + json.dumps({"check": check, "model": model, "key": key})
    call = subprocess.run(["docker", "exec", "-i", API_CONTAINER, "python", "-c", loader, "--container"],
                          input=data, text=True, encoding="utf-8", capture_output=True, timeout=420)
    for line in call.stdout.splitlines():
        if line.startswith("MES_SETUP_RESULT="):
            return line.partition("=")[2]
    raise SetupError("Dify provider setup failed. Check the official plugin installation and DeepSeek credentials in Studio, then retry.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Read-only check; never installs or sets credentials")
    parser.add_argument("--model", default="deepseek-flash")
    args = parser.parse_args()
    verify_local_dify()
    register_if_new(args.check)
    state = configure(args.check, args.model)
    if state == "needs_deepseek_key" and not args.check:
        key = os.environ.get("MES_SETUP_DEEPSEEK_API_KEY") or getpass.getpass("Your DeepSeek API key (saved only in Dify): ")
        if key.strip():
            state = configure(False, args.model, key.strip())
    if state != "ready":
        raise SetupError(f"Dify provider not ready ({state}). Open local Studio, verify the agent and DeepSeek plugins and chosen model, then rerun.")
    print("Dify agent plugin and selected DeepSeek model are available.")


if __name__ == "__main__":
    try:
        if "--container" in sys.argv:
            print("MES_SETUP_RESULT=" + in_workspace(json.load(sys.stdin)))
        else:
            main()
    except Exception as exc:
        if "--container" in sys.argv:
            print("MES_SETUP_RESULT=provider_error_" + type(exc).__name__)
        else:
            print(str(exc) if isinstance(exc, SetupError) else "Dify setup did not finish (" + type(exc).__name__ + ").", file=sys.stderr)
        sys.exit(1)
