"""Read-only live browser test. MES_TRACE_USER/PASSWORD must be a reader with all three domains."""
import json
import os
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlsplit

import pymysql
import requests
from playwright.sync_api import expect, sync_playwright

from replay import MES


def reference():
    config = Path(__file__).resolve().parents[1] / "deploy/.env.agent-read.local"
    env = dict(line.split("=", 1) for line in config.read_text("utf-8-sig").splitlines()
               if "=" in line and not line.startswith("#"))
    url = urlsplit(env["MES_AGENT_JDBC_URL"].removeprefix("jdbc:"))
    with pymysql.connect(host=url.hostname, port=url.port or 3306, db=url.path.strip("/"),
                         user=env["MES_AGENT_JDBC_USER"], password=env["MES_AGENT_JDBC_PASSWORD"]) as db, db.cursor() as cur:
        cur.execute("START TRANSACTION READ ONLY")
        def read(sql):
            cur.execute(sql)
            return set(cur.fetchall())
        work = read("SELECT work_order_id,work_order_no FROM work_order WHERE status IN "
                    "('DRAFT','QC_PENDING','CREATED','PENDING','RELEASED','DISPATCHED','RUNNING','PRODUCING','PAUSED')")
        dispatch = read("SELECT work_order_id,dispatch_no,u.real_name FROM dispatch_task d "
                        "LEFT JOIN user u ON u.user_id=d.operator_id")
        orders = read("SELECT order_id,audit_status FROM customer_order")
        approved_work=read("""SELECT w.work_order_id,w.work_order_no FROM work_order w
                              JOIN production_plan p ON w.plan_id=p.plan_id
                              JOIN production_plan_item pi ON pi.plan_item_id=w.plan_item_id AND pi.plan_id=p.plan_id
                              JOIN customer_order_item i ON i.order_item_id=pi.order_item_id AND i.material_id=pi.material_id AND i.material_id=w.material_id
                              JOIN customer_order o ON o.order_id=i.order_id AND o.order_id=p.source_order_id WHERE o.audit_status='APPROVED'""")
        stock = read("SELECT inventory_id,quantity_reserved,warehouse_code FROM inventory")
        # Independent SQL arithmetic for this fixture. Ambiguous or multi-level BOMs
        # need their own controlled fixtures; they must not count as zero demand.
        materials = read("""SELECT m.material_name,SUM((w.planned_quantity-w.completed_quantity)*b.quantity*(1+b.loss_rate)),
                         (SELECT SUM(v.quantity_available) FROM inventory v WHERE v.material_id=b.child_material_id AND v.inventory_status='NORMAL')
                         FROM work_order w JOIN bom b ON b.parent_material_id=w.material_id
                         JOIN material m ON m.material_id=b.child_material_id WHERE w.status IN
                         ('DRAFT','QC_PENDING','CREATED','PENDING','RELEASED','DISPATCHED','RUNNING','PRODUCING','PAUSED')
                         AND b.status=1 AND b.effective_date<=CURRENT_DATE AND (b.expire_date IS NULL OR b.expire_date>=CURRENT_DATE)
                         GROUP BY b.child_material_id,m.material_name""")
        return dict(empty=set(), materials={name:(need,stock) for name,need,stock in materials}, works={i for i,_ in work}, numbers={s for _,s in work},
                    dispatch={(n,u) for i,n,u in dispatch if i in {k for k,_ in work}},
                    orders={i for i,_ in orders}, approved={i for i,s in orders if s=="APPROVED"},
                    approved_work={i for i,_ in approved_work},
                    approved_dispatch={(n,u) for i,n,u in dispatch if i in {k for k,_ in approved_work}},pending={i for i,s in orders if s=="PENDING"},
                    stock={i for i,_,_ in stock}, reserved={i for i,q,_ in stock if q and q>0},
                    wh_reserved={i for i,q,w in stock if q and q>0 and w=="WH-01"})


def conversation(login, user, oracle, turns):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="msedge")
        try:
            page = browser.new_page()
            page.goto("http://localhost:5173/login")
            page.evaluate("""([token,user])=>{sessionStorage.setItem('token',token);
                          sessionStorage.setItem('userInfo',JSON.stringify(user))}""",[login["token"], user])
            # Chromium may discard a consumed streaming response before CDP asks for its body.
            # Tee the real fetch once; both the UI and the test receive the original bytes.
            page.add_init_script("""window.testStreams=[];const request=window.fetch;
              window.fetch=async (...args)=>{const response=await request(...args);
                if(new URL(response.url).pathname.endsWith('/runs/stream')){
                   window.testStreams.push(response.clone().text());
                }return response};""")
            page.goto("http://localhost:5173/assistant")
            streams = []
            page.on("response", lambda r: streams.append(r) if r.request.method == "POST"
                    and r.url.endswith("/runs/stream") else None)
            last = None
            for index, (question, domain, key) in enumerate(turns):
                start=len(streams)
                page.get_by_role("textbox", name="输入问题").fill(question)
                send=page.get_by_role("button", name="发送问题")
                send.click()
                msgs=page.locator("article.workbench-message.assistant")
                expect(msgs).to_have_count(index+1,timeout=20000)
                expect(msgs.last.locator(".message-actions")).to_be_visible(timeout=180000)
                expect(send).to_be_visible(timeout=30000)
                if domain=="CHAT":
                    assert len(streams)==start and not msgs.last.locator(".message-error").count(),question
                    print(question,"→ 日常回答，0 个业务运行",flush=True)
                    continue
                assert len(streams)==start+1,(question,msgs.last.inner_text()[:160])
                req=streams[-1].request.post_data_json
                is_followup=last is not None
                if last:
                    assert req.get("previousRunId")==last,question
                frames=[json.loads(line[5:]) for line in page.evaluate("() => window.testStreams.at(-1)").splitlines() if line.startswith("data:")]
                events=[e["payload"]["legacy"] for e in frames if "legacy" in e["payload"]]
                last=next(e["runId"] for e in events if e["type"]=="run")
                result=next(e["data"] for e in events if e["type"]=="result")
                assert result["verdict"]=="EVIDENCE_READY",(question,result["verdict"],result["reply"][:160])
                facts=result["facts"]
                if is_followup:
                    assert facts[domain]["overview"]["countBasis"]=="CURRENT_ROWS_OF_PREVIOUSLY_DISPLAYED_RECORDS",question
                ids={int(r["record_id"]) for r in facts[domain]["overview"]["rows"]}
                assert ids==oracle[key],(question,ids,oracle[key])
                deltas=sum(1 for e in events if e["type"]=="delta" and e.get("text"))
                assert deltas and any(e["type"]=="tool_completed" for e in frames)
                extra=""
                if domain=="order" and "负责" in question:
                    assert {int(w["record_id"]) for w in facts["production"]["overview"]["rows"]}==oracle["approved_work"]
                    assert {(d["dispatch_no"],d.get("operator_name")) for d in facts["production"]["dispatchRows"]}==oracle["approved_dispatch"]
                    extra=f"，关联工单 {len(oracle['approved_work'])} 张"
                if domain=="production" and ("派给谁" in question or "谁负责" in question):
                    rows=facts["production"]["dispatchRows"]
                    assert {(r["dispatch_no"],r.get("operator_name")) for r in rows}==oracle["dispatch"]
                    materials=facts["inventory"]["materialRows"]
                    assert {m["material_name"] for m in materials}==oracle["materials"].keys()
                    for m in materials:
                        need,available=oracle["materials"][m["material_name"]]
                        assert Decimal(m["scenario_demand_qty"])==need
                        assert (Decimal(m["public_available_qty"]) if m["public_available_qty"] is not None else None)==available
                        assert set(m["work_order_no"].split("、"))<=oracle["numbers"]
                        if m["scenario_gap_qty"] is not None:
                            assert Decimal(m["scenario_gap_qty"])==max(0,Decimal(m["scenario_demand_qty"])-Decimal(m["public_available_qty"]))
                    assert materials or "未覆盖" in facts["inventory"]["traceConclusion"]
                    expect(msgs.last.get_by_text("所选工单物料需求与账面缺口",exact=True)).to_be_visible(timeout=15000)
                    gaps=[str(m["material_name"])+" "+str(m["scenario_gap_qty"])+str(m["unit"]) for m in materials if m["scenario_gap_qty"] is not None and Decimal(m["scenario_gap_qty"])>0]
                    extra=f"，缺口 {gaps}，派工 {len(rows)} 条，物料 {len(materials)} 种"
                print(f"{question} → {len(ids)} 行，{deltas} 个文本增量{extra}",flush=True)
        finally:
            browser.close()


def main():
    oracle=reference()
    suites=[
        [("哪些工单还没做完","production","works"),("这几单派给谁了、缺哪些物料","production","works"),
         ("今天天气怎么样","CHAT",""),("回到刚才那几单","production","works")],
        [("帮我列一下尚未完工的生产工单","production","works"),("刚才那些工单由谁负责，物料齐套了吗？","production","works")],
        [("列出全部订单","order","orders"),("其中哪些还没审核？","order","pending"),
         ("讲个短笑话","CHAT",""),("接着看前面那批订单","order","pending")],
        [("查询已审核的订单","order","approved"),("刚才这些订单对应的工单由谁负责？","order","approved"),
         ("回到刚才的订单","order","approved")],
        [("帮我查询库存","inventory","stock"),("这些库存哪些有预留？","inventory","reserved"),
         ("其中仓库 WH-01 的有哪些？","inventory","wh_reserved"),("回到刚才那批库存","inventory","wh_reserved"),
         ("哪些还没有预留？","inventory","empty"),("接着看刚才那几条记录","inventory","empty")],
    ]
    with requests.Session() as c:
        c.trust_env=False
        login=c.post(MES+"/auth/login",json=dict(username=os.environ["MES_TRACE_USER"],
                     password=os.environ["MES_TRACE_PASSWORD"]),timeout=12).json()["data"]
        c.headers["Authorization"]="Bearer "+login["token"]
        try:
            user=c.get(MES+"/auth/userInfo",timeout=10).json()["data"]
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(conversation,login,user,oracle,suite) for suite in suites]
                failures=[]
                for future in futures:
                    try: future.result()
                    except Exception as exc: failures.append(exc)
                if failures: raise ExceptionGroup("Live conversation failures",failures)
        finally:
            c.post(MES+"/auth/logout",timeout=10)


if __name__=="__main__":
    main()
