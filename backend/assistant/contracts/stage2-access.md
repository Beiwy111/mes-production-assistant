# 第二阶段接入契约

以下是开启 assistant.v2.enabled 后的新链路。浏览器开发代理额外加 /api；后端路径本身不含 /api。关闭时原助手行为保持原分支。

## 问答请求

POST /assistant/interpret，或 POST /assistant/interpret/stream。兼容旧 /afterSales/assistant 别名。Authorization: Bearer 使用 MES 登录 token，不是 Dify 密钥。

```json
{
  "text": "你现在支持什么？",
  "requestId": "客户端为本次请求生成的唯一编号",
  "sessionId": "当前聊天会话编号",
  "conversation": [{"role": "user", "text": "上一条问题"}]
}
```

text 必须是非空字符串，trim 后最多 4000 字符。requestId/sessionId 最多 160 字符且不能包含控制字符；requestId 缺省时服务端生成，无法据此重放；sessionId 缺省时使用当前 requestId，保证有 requestId 的相同请求可以重放。

仅保留 user/assistant 历史，取最近最多 12 条，每条内容最多 4000，合计最多 12000。历史不可信，不授予身份或工具权限。请求中的 user/role/tools/module 等字段不影响新链路权限。用户身份取服务端会话 userId。

## 响应

普通接口沿用 Result 包装：code=200、data 包含 type=answer、reply、sessionId、requestId、runId、provider=dify、streamed=false。

流式接口为 NDJSON：

```text
{"type":"run","runId":"本地运行编号","requestId":"请求编号"}
{"type":"status","status":"RUNNING"}
{"type":"delta","text":"成功后的最终回答"}
{"type":"result","data":{"type":"answer","reply":"成功后的最终回答","runId":"...","requestId":"...","sessionId":"...","provider":"dify","streamed":true}}
{"type":"done"}
```

status 可选；成功缓存重放可能没有 status。失败返回 error/message/runId，不返回成功 result/done。新链路只产生 answer，不产生旧 confirm/proposalId。当前 delta 是经过完成状态检查后的最终文本，不是逐 token 输出。

鉴权、输入和重复请求错误在打开流之前抛出，沿用原项目全局错误包装，可能是 HTTP 200 携带非 200 code。前端同时处理 HTTP 错误与该包装，不把它误当成正常流。

## 状态与取消

- GET /assistant/v2/runs/{runId}
- POST /assistant/v2/runs/{runId}/cancel

两接口都要求 MES 登录，并且 runId 属于该用户。返回 Result 包装，data 为 runId、requestId、sessionId、status、createdAt；只有 SUCCEEDED 才带 reply。不会返回上游 task_id 或密钥。

状态：RUNNING、SUCCEEDED、FAILED、CANCELLED。CANCELLED 表示本地已取消，不表示上游已确认停止。已完成记录再次取消保持原终态。不同用户或过期 runId 返回相同的 404 业务错误。

同用户同 requestId：成功且输入相同则返回缓存；内容不同/仍在执行/已失败或取消返回 409。全局 4 个活动请求、每用户 1 个、记录容量 1000，超额返回 429；本阶段为内存存储，重启不保留。

## Java→Dify

仅调用 POST /workflows/run，inputs 为 question、history（JSON 字符串），response_mode=streaming，user 由 Java 生成 mes-user-{userId}。Dify 应用密钥只放 Authorization 头。不同工作流节点如何推理完全由 Dify 负责。

只接受 workflow_finished、data.status=succeeded、outputs.answer 非空字符串。拒绝截断、超大帧、空/非文本输出；内部节点内容不转发。超时、断连或取消后，若已有合法 task_id，则尽力发送一次 stop；不跟随重定向、不自动重放请求。
