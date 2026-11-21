# 第三阶段知识接口：公开文献域

Python仅绑定127.0.0.1:18081。Dify Docker通过host.docker.internal:18081访问。除GET /health外，必须使用Authorization: Bearer <本地知识服务密钥>；此密钥不代表MES用户身份。health仅证明进程存活。

## 查询

POST `/internal/knowledge/query`

```json
{"query":"Mura缺陷如何检测？","top_k":5}
```

query非空，最长2000字符；top_k为整数1–8，默认5。拒绝额外字段，包括角色、租户、SQL、过滤器。E5查询超过512token时由tokenizer截断，适合短问题；不要提交整份报告。文档连同元数据超过模型预算则拒绝发布，不截断正文。

| 返回字段 | 口径 |
|---|---|
| release_id | 请求使用的当前发布版本，32位十六进制 |
| knowledge_domain | 固定display_reference |
| status | MATCHES/NO_MATCHES只表示有无检索结果，不表示证据充分 |
| hits | 最多top_k条，不返回向量 |
| limitations | 研究参考、相似度非正确概率、PDF提取可能丢失版式/公式 |

每条hit包含document_id、document_version（PDF SHA-256）、chunk_id、title、text、page_start/page_end（PDF页码，从1开始）、source_url、license、authority_type=PUBLIC_RESEARCH、release_id、evidence_ref、evidence_path、score。score为未校准的余弦相似度；不冒充正确概率。文本修订通过发布清单text_sha256区分。

## 引用回查和版本

GET `/internal/knowledge/evidence/{release_id}/{chunk_id}`，同一鉴权；返回hit内容，不含score。只服务当前活跃版本及未撤回文献；旧版本、已撤回或不存在的引用返回404。原件保留在本地归档，没有开放任意文件下载接口。

请求结束前再次检查活跃版本/撤回状态。发布变化中的查询失败，让调用方重试；这不是分布式事务，不保证响应传输结束之后仍未撤回。Dify本轮不自动重试；失败不会让LLM补写事实。

## 错误与边界

401未认证；422输入非法/额外字段；429CPU查询槽占用；503索引、模型或后端不可用，响应不包含内部异常。单进程仅并发执行一个查询，不是生产级队列/限流。

Qdrant查询进程使用只读key；入库独立读取writer key。查询过滤公共域、审核状态、活跃generation、文献ID及撤回清单。项目卡不入库，不能通过模型参数扩权。

当前不支持私有工艺授权、图谱、业务数量、业务用户审计或Dify跨服务授权。未来加入内部知识须增加Java可信身份与授权范围传递，不能用公共服务key替代用户登录令牌。
