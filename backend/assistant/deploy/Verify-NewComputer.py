"""Read-only checks for the local release. --live goes through the MES and model APIs."""
import argparse
import getpass
import json
import os
import sys
from pathlib import Path
from uuid import uuid4

import httpx

DEPLOY = Path(__file__).resolve().parent
GATE = "http://127.0.0.1:18083/assistant-workbench"
MES = "http://127.0.0.1:8088"
ROLES = {"KNOWLEDGE", "ORDER", "SUPPLY", "PRODUCTION", "QUALITY", "EQUIPMENT", "AFTERSALES", "SUP"}


class CheckError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise CheckError(message)


def field(path, name):
    prefix = name + "="
    entries = [s[len(prefix):] for s in path.read_text(encoding="utf-8-sig").splitlines() if s.startswith(prefix)]
    require(len(entries) == 1 and entries[0].strip(), f"Missing or duplicate {name} in {path.name}.")
    return entries[0]


def installation(client):
    ids = json.loads((DEPLOY / ".env.stage5-bindings.local").read_text(encoding="utf-8-sig"))
    require(set(ids["apps"]) == ROLES, "Dify publication receipt is incomplete.")
    token = field(DEPLOY / ".env.workbench.local", "MES_WORKBENCH_DIFY_API_KEY")
    # Same credential the gateway uses. Never send it to a configurable or redirected URL.
    api = client.get("http://127.0.0.1:18080/v1/parameters", headers={"Authorization": "Bearer " + token})
    api.raise_for_status()
    info = client.get("http://127.0.0.1:18080/v1/info", headers={"Authorization": "Bearer " + token})
    info.raise_for_status()
    require(info.json().get("name") == ids["apps"]["SUP"]["name"],
            "The application key belongs to another workflow. Rerun the local Dify publication step.")
    fields = {v.get("variable") for item in api.json()["user_input_form"] for v in item.values() if isinstance(v, dict)}
    require({"query", "run_ref", "order_intake"} <= fields, "The application key does not resolve to the published SUP contract.")
    health = client.get("http://127.0.0.1:18082/health")
    health.raise_for_status()
    require(health.json().get("status") == "OK", "Agent runtime is unhealthy.")
    require(client.get(GATE + "/status").status_code == 401, "Gateway should require a MES session.")
    print("[OK] Published supervisor contract, workspace receipt, Agent runtime and authenticated gateway.")


def check_live(client, order):
    username = os.getenv("MES_TRACE_USER") or input("MES login (use an authorized read account): ").strip()
    password = os.getenv("MES_TRACE_PASSWORD") or getpass.getpass("MES password: ")
    login = client.post(MES + "/auth/login", json={"username": username, "password": password})
    login.raise_for_status()
    body = login.json()
    require(body.get("code") == 200 and body.get("data", {}).get("token"), "MES login failed.")
    headers = {"Authorization": "Bearer " + body["data"]["token"]}
    res = client.get(GATE + "/status", headers=headers)
    res.raise_for_status()
    flags = res.json()
    require(all(flags.get(k) is True for k in ("configured", "naturalLanguage", "directChat")), "Gateway lacks the current Dify or daily-chat configuration; restart the project-owned gateway after setup.")
    for text, route in [("用一句话解释为什么天空看起来是蓝色的。", "GENERAL"), ("请查询中国上海市明天的天气预报。", "WEATHER")]:
        resp = client.post(GATE + "/chat-entry", headers=headers, json={"text": text, "context": "NEW"})
        resp.raise_for_status()
        events = [json.loads(line) for line in resp.iter_lines() if line]
        answer = "".join(e.get("text", "") for e in events if e.get("type") == "delta")
        require(any(e.get("type") == "result" and e.get("data", {}).get("route") == route for e in events)
                and answer.strip(), f"{route} failed; check the DashScope account and network.")
        if route == "WEATHER":
            require("open-meteo.com" in answer, "Weather routing works but the live forecast did not return. Check outbound access to Open-Meteo.")
        print(f"[OK] {route} routed and answered.")
    res = client.post(MES + "/assistant-v2/knowledge/explore", headers=headers, json={"query": "如何检测液晶面板的 Mura 缺陷？"})
    res.raise_for_status()
    evidence = res.json()
    require(evidence.get("code") == 200 and (evidence.get("data") or {}).get("hits"), "No authorized knowledge hits through the MES API.")
    print("[OK] MES retrieved cited knowledge.")
    if order is None:
        return
    if not order.isascii() or not order.isdecimal() or int(order) < 1:
        raise CheckError("Use a positive numeric order ID.")
    domains = set(flags.get("capabilities", {}).get("domains", []))
    require({"DELIVERY", "ORDER", "INVENTORY", "PRODUCTION"} <= domains,
            "This MES account lacks access to the combined order-delivery check. "
            "Choose an authorized delivery account or omit --order to test daily chat and knowledge.")
    payload = {"text": f"订单 {order} 能按期交付吗？请分别核对可发货数量、物料齐套情况和生产排程。",
               "sessionId": str(uuid4()), "requestId": str(uuid4())}
    agents, ended, deltas = set(), False, 0
    with client.stream("POST", GATE + "/runs/stream", headers={**headers, "Accept":"text/event-stream"},
                       json=payload, timeout=300) as stream:
        stream.raise_for_status()
        for line in stream.iter_lines():
            if not line.startswith("data: "):
                continue
            event = json.loads(line[6:])
            if event.get("type") == "agent_completed" and event.get("agent_id") in ROLES - {"SUP", "KNOWLEDGE"}:
                agents.add(event["agent_id"])
            if event.get("type") == "task_completed":
                ended = True
            if event.get("type") == "message_delta":
                deltas += 1
    require(ended and deltas and len(agents) >= 2, "No completed multi-Agent delivery answer. Verify the order, MES permissions and Dify tool calls.")
    print("[OK] Streamed answer with completed specialists: " + ", ".join(sorted(agents)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="MES sign-in and model calls (provider usage applies)")
    parser.add_argument("--order", help="Optional existing order ID visible to the test account")
    args = parser.parse_args()
    if args.order and not args.live:
        parser.error("--order requires --live")
    try:
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=90) as client:
            installation(client)
            if args.live:
                check_live(client, args.order)
    except Exception as exc:
        sys.exit(str(exc) if isinstance(exc, CheckError) else f"Acceptance failed ({type(exc).__name__}). Check the local service and retry.")
