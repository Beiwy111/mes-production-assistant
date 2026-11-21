# Stage 5 independent read-only runtime

This document retains the initial Stage 5 contract. The current Q1/D1/A1 sources, workflowProfile inputs, EVIDENCE_READY result and stock/material scenarios are specified in [the business-evidence extension](stage6-business-evidence.md), which takes precedence over the unsupported-source descriptions below.

Entry point: `com.upc.computer.assistant.v2.runtime.Stage5RuntimeMain`. This is a JDK HTTP server on `127.0.0.1:18082`, with no Spring application startup, component scan, scheduler, controller registration, or MES login impersonation. Its internal registry identity is a private negative preview identity. The credentials below authorize trusted local maintenance preview only; they do not represent MES end-user authorization.

## Configuration and database boundary

Required process environment:

| Variable | Meaning |
| --- | --- |
| `MES_AGENT_BOOTSTRAP_KEY` | Private maintenance launcher credential; never given to a model or expert |
| `MES_AGENT_SUP_KEY` | Supervisor context/finalization credential |
| `MES_AGENT_ORDER_KEY`, `MES_AGENT_SUPPLY_KEY`, `MES_AGENT_PRODUCTION_KEY` | Core expert credentials |
| `MES_AGENT_QUALITY_KEY`, `MES_AGENT_EQUIPMENT_KEY`, `MES_AGENT_AFTERSALES_KEY` | Unsupported-source expert credentials |
| `MES_AGENT_ALLOWED_ORDER_IDS` | Explicit comma-separated positive decimal primary keys |
| `MES_AGENT_JDBC_URL`, `MES_AGENT_JDBC_USER`, `MES_AGENT_JDBC_PASSWORD` | Private MySQL connection settings |
| `MES_AGENT_PORT` | Optional compatibility setting; only `18082` is accepted because the published Dify endpoints are fixed |

All eight keys must be distinct URL-safe strings of 32–256 characters. Generate cryptographically random values; do not use test fixture keys. Keys, JDBC settings and raw failures are never included in HTTP responses. Supply keys only through fixed HTTP-node secret variables. HTTP authentication is `Authorization: Bearer <key>`.

`MES_AGENT_JDBC_PASSWORD` must be explicitly present. An intentionally empty value is preserved for an existing local database account with an empty password; missing/null configuration is rejected and there is no implicit password default. JDBC URL and username must remain nonblank.

The factory registers only `AiDeliveryReadMapper`, rejects non-SELECT mapped statements, disables caching and sets a 15-second SQL statement timeout. Every business operation gets a fresh MyBatis session and explicitly sets JDBC read-only, repeatable-read and manual transaction mode before mapper access. It verifies those connection settings and always forces rollback and closes the session, including failure paths. E0, E2, E3 and E5 are individual operations; ORDER E1+E4 share one operation. The existing E0–E5 implementation and mapper are reused unchanged. No business mutation is provided.

Read-only connection mode is additional protection to fixed SELECTs. It is not a claim that the configured account lacks write grants; use a dedicated read-only database account when provisioning the preview. The runtime never attempts a write to test permissions.

## HTTP contract

All request bodies are JSON objects; unknown/duplicate fields, trailing JSON, query strings, client-supplied facts/references/roles and encoded paths are rejected. Request bodies are limited to 32 KiB. Errors are sanitized `{ "error": "CODE" }`; unauthorized is 401, role/order denial 403, unavailable reference 404, dependency/result conflicts 409, expired/cancelled runs 410, capacity 429 and database/read failure 503. `/health` is the only unauthenticated endpoint and returns only `{ "status": "OK" }`.

| Method/path | Credential | Body / behavior |
| --- | --- | --- |
| `POST /stage5/runs` | BOOTSTRAP | `{ "orderPk":"10", "targetDate":"2026-12-01", "targetKind":"FACTORY_SHIPMENT" }`; date/kind optional; allowlist checked before any SQL; 201 |
| `GET /stage5/runs/{runRef}/context` | SUP | Scope, required agents, source mode and existing completed receipts |
| `POST /stage5/runs/{runRef}/tasks/{ROLE}/begin` | Exact ROLE | `{}`; obtains immutable server task package, 200 |
| `POST /stage5/runs/{runRef}/tasks/{ROLE}/complete` | Exact ROLE | `{ "summary":"..." }`; nonblank string up to 6000 Java characters; server-generated receipt, 200 |
| `POST /stage5/runs/{runRef}/finalize` | SUP | `{}`; deterministic E5-backed assessment, 200 |
| `POST /stage5/runs/{runRef}/cancel` | BOOTSTRAP | `{}`; invalidates all task access, 200 |

No list-runs route or arbitrary SQL/order lookup exists. Run references are random, server-issued and bound to one E0 scope. At most 64 runs remain resident; each expires 600 seconds after creation starts. Registry limits remain 4096 query refs and 256 scopes. Expired records are reclaimed on new run creation; a reclaimed ref returns 404, while a still-resident expired/cancelled ref returns 410. Cancellation does not erase audit references before expiry. State is process-local and lost on restart.

Create/context response:

```json
{
  "runRef":"<server UUID>",
  "scope":{"orderPk":"10","targetKind":"FACTORY_SHIPMENT","targetDate":null,"scopeRef":"<E0 scope>","expiresAt":"<UTC instant>"},
  "requiredAgents":["ORDER","SUPPLY","PRODUCTION"],
  "sourceMode":"LOCAL_READONLY_PREVIEW",
  "taskReceipts":[]
}
```

ORDER begin obtains E1 and E4. SUPPLY begin requires a completed ORDER receipt and queries E2 using server-stored E1/E4 refs. PRODUCTION begin requires completed ORDER and SUPPLY receipts and queries E3 using server-stored E1/E2 refs. A summary cannot substitute for an upstream receipt. Different roles cannot read or complete sibling paths; a run cannot import references from another run. Credentials intentionally authorize their role in this local preview; run references must also be kept in the trusted workflow context.

Task begin returns `taskId`, `agentId`, `runRef`, `status`, `evidence` (unchanged E envelopes), immutable `upstreamResultRefs`, `sourceMode` and `startedAt`. Core status is `PARTIAL`. QUALITY/EQUIPMENT/AFTERSALES return `UNSUPPORTED_BUSINESS_SOURCE` and an empty evidence list. They may discuss separately labeled public knowledge but cannot invent business evidence.

Complete returns those same task fields plus `resultRef`, `modelCommentary`, `commentaryTrust: UNTRUSTED_MODEL_COMMENTARY`, and `completedAt`. It does not accept task/result/query refs, quantities, statuses or verdicts. Repeated begin returns the original package, including concurrent requests. Repeated complete with identical summary returns the original receipt; changed summary returns `RESULT_CONFLICT`. Failed begin never publishes a usable task.

## Finalization and interpretation

Finalize calls E5 for the server's registered E0–E4 query refs. It never reads numbers or a verdict from model commentary. Response fields:

- `runRef`, `sourceMode`, `verdict`, `checkedAt`, `executedAgents` (tasks actually begun), `missing`.
- `queryRefs`, `resultRefs`: server-registered references only.
- `knowledgeExplanations`: completed QUALITY/EQUIPMENT/AFTERSALES commentary and resultRef, separately marked UNVERIFIED_MODEL_EXPLANATION and UNSUPPORTED_BUSINESS_SOURCE. This is not business evidence, and neither the text nor its citations is promoted into facts or verdict.
- `facts.fulfillmentLines`: E4 `data.lines`; `facts.materials`: E2 `data.materials`; `facts.productionWorkOrders`: E3 `data.workOrders`; `facts.estimatedShipmentAt`: null.
- `revalidation`: E5 envelope, omitting raw `data.replacementEvidence` to avoid duplicating source tables.

Missing required completed agents yield `INCOMPLETE`; callers can continue those tasks. Changed fingerprints yield `DATA_CHANGED`, add `REFRESH_RUN_REQUIRED` and set exposed fact collections to null; start a fresh run. Otherwise the verdict is always `INSUFFICIENT_EVIDENCE`, because existing E interfaces are partial and cannot prove reservation ownership, quality/inbound reconciliation, complete production capacity or arrival. Unknown quantities remain JSON null. Public RAG/GraphRAG text never fills these missing facts.

Completed or changed final assessments are stable/idempotent until expiry; the `checkedAt` timestamp identifies the actual E5 assessment time. New tasks cannot begin after terminal finalization. A cached assessment is not a fresh database read or delivery promise. An incomplete finalization is not sealed and can be repeated after task progress. Transport/tool failures must be displayed as failures, never converted to a successful assessment by a model.

## Verification boundary

The stage 5 unit/HTTP tests use real JDK HTTP, the real E0–E5 calculations and registry with mocked external mapper data. Separate adapter tests exercise the transaction lifecycle against mocked JDBC/MyBatis resources. These establish application behavior, not a live database permission audit. Actual local MySQL/runtime/Dify integration must be recorded separately with redacted evidence; no database is contacted by these tests.
