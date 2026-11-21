# Face Auth API

所有路径相对现有后端（默认 localhost:8088），前端经 `/api` 代理访问。保持统一 `Result {code,message,data}` 约定：业务错误使用 HTTP 200 + code 400/401/403/409/422/429/503；过大请求在读取前/读取时返回 HTTP 413。响应均 `Cache-Control: no-store`。错误不返回上游原始响应或异常堆栈。

| Method / path | 权限 | 输入与输出 |
|---|---|---|
| GET /auth/face/capabilities | 匿名 | enabled、configured、loginAllowed、demoOnly=true、livenessAvailable=false、consentVersion、notice、maxImageBytes |
| POST /auth/face/preview | 匿名、当前授权、独立来源限流 | `{imageBase64,consent:true,consentVersion}` -> `{ready,message,box?}`；只检测当前画面，无用户查询、绑定变更或登录会话 |
| POST /auth/face/challenge | 匿名、限流 | `{username}` -> `{challengeId}`；默认 120 秒，仅可使用一次，绑定账号及服务器看到的来源地址 |
| POST /auth/face/login | 匿名、限流 | `{username,challengeId,imageBase64,consent:true,consentVersion}` -> `{token,demoOnly:true}` |
| GET /auth/face/me | 当前有效账号 | `{enrolled,state,bindingRevision,updatedAt,consentAt,pendingCleanup,demoOnly}` |
| PUT /auth/face/me | 当前有效账号 + 当前密码 | `{imageBase64,password,consent:true,consentVersion}` -> 状态；只操作当前用户，忽略客户端自报的 userId |
| POST /auth/face/me/test | 当前有效账号 + 当前密码 + 参与者授权 | 同录入请求字段 -> `{similarity,threshold,matched,bindingRevision,demoOnly:true}`；仅比较当前账号绑定，不修改样本、不签发 token；不同人被拒绝也返回 code=200、matched=false |
| DELETE /auth/face/me | 当前有效账号 + 当前密码 | JSON `{password}` -> `{enrolled:false,state:NONE,pendingCleanup,demoOnly}` |
| GET /auth/face/users/{userId} | 当前有效 ADMIN | 指定用户状态，不返回图片、特征、Subject 或 image_id |
| DELETE /auth/face/users/{userId} | 当前有效 ADMIN + 管理员自己的密码 | JSON `{password}` -> 撤销状态；不能用目标用户密码替代当前管理员的再次验证 |
| POST /auth/logout | 现有登录会话 | 原接口，沿用现有 Redis 会话删除 |

登录和管理请求的 consentVersion 必须来自 capabilities 的当前版本。imageBase64 允许纯 Base64，或 `data:image/jpeg;base64,...` / PNG Data URL。拒绝其他媒体类型、无效图像、大于 3MB、尺寸小于 240x240 或总像素超过 400 万的输入。摄像头前端输出 JPEG；后端去元数据重编码，远端请求采用 multipart，不使用浏览器直接调用 CompreFace。

当前授权版本 `face-demo-2026-11-v3`。Web 录入/更新先自动采集，展示将提交的原始帧及目标账号，经用户确认后才调用 PUT /me；确认使用的 Base64 与展示画面完全相同，不在确认时另拍一帧。确认是避免误操作的交互步骤，服务端仍以有效会话、当前密码、授权及图像质量校验作为录入条件。

质量条件：Detection/verify 的完整画面结果恰好一张人脸，检测概率默认 >=0.95，人脸框边长 >=100px，脸框区域亮度/清晰度通过启发式检查。1:1 相似度默认 >=0.85。阈值是演示初始配置，未声称适用于所有摄像头或人群；需要授权样本实测误接受/误拒绝后校准。

默认保护：账号最多 5 次失败尝试/5 分钟；来源最多 30 次登录尝试/5 分钟；challenge 也限流；管理操作每账号最多 10 次/5 分钟。账号/来源以散列值作为 Redis key，成功验证清除账号失败计数，来源计数不清除。只使用服务器 remoteAddr，不相信客户端自填 X-Forwarded-For。部署可信反向代理时需单独正确配置客户端地址转发，否则可能把共享代理视为同一来源。

自动检测 `preview` 每来源最多 120 次/默认 5 分钟（窗口沿用 attemptWindowSeconds），使用独立 Redis 计数，不消耗登录或管理次数；每个后端进程最多同时处理 2 个预检测请求，超出立即返回 503。画面不合格返回 `code=200, data.ready=false` 和固定调整提示；服务故障/限流返回 503/429，前端停止采集。合格时 `box` 包含归一化到 0–1 的 `x,y,width,height`，仅用于当前画面的框选及连续两帧位置稳定判断，不含身份、特征或 Key。预检测不会添加/修改 CompreFace 样本，也不写入每帧数据库审计。最后的 enroll/login 仍重新检查图像质量、密码/权限或账号匹配，不能将客户端 ready 标志当成认证依据。

## CompreFace 1.2.0 适配

- Detection: `POST /api/v1/detection/detect?limit=0&det_prob_threshold=...`，Detection API Key。
- Enrollment: `POST /api/v1/recognition/faces?subject=...&det_prob_threshold=...`，Recognition API Key。
- 1:1 verification: `POST /api/v1/recognition/faces/{image_id}/verify?limit=0&det_prob_threshold=...`，Recognition API Key。
- Scoped deletion: `DELETE /api/v1/recognition/subjects/{subject}`，删除对应 Subject 及样本，404 视为已经不存在。绝不调用删除全部 Subjects/全部 faces 的接口。

不请求年龄、性别或特征向量插件；不需要识别人群中其他用户。独立 Detection Key 用于录入前检查人数，避免样本添加接口可能挑选人脸而漏掉多人场景。

更新/删除的不确定结果有持久化清理记录。更新失败的临时 Subject 延迟至少 120 秒后清理，允许远端在途调用结束；中断超过 5 分钟的 ENROLLING 会被恢复。30 秒周期任务重试，每次最多 20 条，失败至少等待 60 秒。管理请求最多同步处理一条清理任务，余项继续显示 pendingCleanup。

审计查询由授权数据库管理员使用：`SELECT actor_user_id,target_user_id,event_type,result_code,created_at FROM mes_face_audit ORDER BY audit_id DESC LIMIT 100;`。未向普通用户开放全量人脸日志接口。

识别排错：新增 `LOGIN_SCORE` / `TEST_SCORE` 事件，result_code 记录 `S=0.97000;T=0.85000;R4` 格式的比对分数、当前有效阈值和绑定版本。它不是人脸向量或识别准确率。历史只有 VERIFIED 的事件无法补算当时的分数；不得据此推断错误放行的具体原因。服务返回缺失、字符串、布尔值、非有限数或范围外的 similarity 时拒绝操作，不再把字符串/布尔值隐式转成数值。

官方接口依据：https://github.com/exadel-inc/CompreFace/blob/v1.2.0/docs/Rest-API-description.md

状态响应另含 `serviceMismatch`，表示当前服务配置与绑定记录不一致。此时禁止验证/更新，仍可撤销本地绑定，但远端清理必须恢复原服务配置后重试。存在 pendingCleanup 时暂停新录入，避免未完成的数据清理被后续更新掩盖。
