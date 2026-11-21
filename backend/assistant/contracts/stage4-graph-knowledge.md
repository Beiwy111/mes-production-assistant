# 第四阶段 GraphRAG 实际契约

公开服务沿用127.0.0.1:18081与原知识服务Bearer key；密钥只认证服务，不是MES用户/订单权限。原query未传mode时仍为vector，第三阶段Workflow不用修改。

## 图增强查询

POST `/internal/knowledge/query`

```json
{"query":"Mura与PGC、APSO有什么关系？","top_k":5,"mode":"graph_assisted"}
```

只支持vector、graph_assisted；query/top_k限制沿用第三阶段。图模式流程：向量召回→别名定位最多4个公共概念→固定Neo4j模板最多2个逻辑声明跳→按图中chunkId回查Qdrant原文→版本/页码/quote核验→交错融合并去重→在证据预算与撤回过滤后重新验证根可达性。不是LLM生成Cypher，也不是微软GraphRAG社区聚类流水线。

新增返回字段：

| 字段 | 含义 |
|---|---|
| mode_requested/mode_used | 请求模式/实际模式，图失败时actual为vector |
| graph_status | MATCHES、NO_MATCHES、NO_ENTITY_MATCH、UNAVAILABLE_OR_STALE |
| graph_release_id | 采用的不可变图版本；没有采用图路径时null |
| resolved_entities | 本次别名匹配的公共概念ID；不是模型生成的业务实体 |
| graph_paths | 最多8条有原文支持的研究声明，含主客体、关系、quote、页码、hops、evidence_refs |
| hits[].retrieval_source | 此融合结果首次来自vector或graph；不把两个来源算独立证据投票 |
| complete | 当前受限图检索/上下文是否完整，绝不表示现实领域知识完整 |
| coverage | CURATED_PUBLIC_CLAIMS_ONLY |
| missing_information | GRAPH_UNAVAILABLE_OR_STALE_VECTOR_ONLY、CONTEXT_BUDGET_TRUNCATED等 |

每条路径保留PUBLIC_RESEARCH、RESEARCH_ONLY和SOURCE_VERIFIED。SOURCE_VERIFIED表示编码助手核对所引原文，不表示用户/厂家已批准的工艺标准。路径只指论文中的方法关系，不证明某批次真实根因。图关系按逻辑声明计算跳数；物理路径Entity→Claim→Entity每个逻辑跳含两个Neo4j关系。

图输出与向量引用同release；任何图证据缺失、被撤回或不符合页码/版本，不能从Neo4j恢复原文。过滤后断开的二跳关系被去除。图服务不可用、维护可写中或版本漂移，明确退回原向量结果；向量本身失败或中途换代则503，不生成无证据答案。

## 固定公共图模板

POST `/internal/knowledge/graph/trace`

```json
{"template_id":"DEFECT_KNOWLEDGE","entity_id":"DEF_MURA","max_paths":8}
```

template_id默认DEFECT_KNOWLEDGE；entity_id必须在当前公共实体清单中；max_paths整数1–8。返回release_id、graph_release_id、graph_paths、hits、complete、truncated、coverage。显式trace无法取得图时503，不冒充图结果降级。未知概念/额外字段422，未认证401。

禁止ORDER_CHAIN、CASE_TRACE、任意Cypher、任意业务ID、roles/tenant/scope等自报授权字段。实时业务数量与私有追溯仍走Java受控接口。维护业务图只有本机CLI，不存在公共business-trace HTTP入口。

## 发布与维护

原Qdrant release保持不变；图全部节点绑定graph_release与对应vector_release。写完校验3文档、125块、11实体、8声明及10证据链接后，原子发布graph-active.json。新图失败保留旧公共图发布指针；向量换代后旧图自动失效，需重新构建。旧generation保留备份，不覆盖在线节点。

业务快照与公开图不同：按明确订单维护同步，SYNCING→READY或FAILED。新包校验失败也将所选订单的旧READY标志失效；恢复成功前维护trace不可用。每个节点和关系均带订单范围与generation，共用Plan逻辑ID不会跨订单串链。complete=true仅为有界导出完整，不代表所有业务链都有记录。

Neo4j Community正常数据库级只读，维护导入通过单机跨进程锁进入可写窗口，并在finally恢复/验证只读。Start与Update脚本共享该锁。不是企业版reader角色隔离；本机管理员仍是受信维护角色，不能据此宣称多租户生产安全。
