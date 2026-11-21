# Stage 4 maintenance order-chain export

This interface is a private maintenance input to graph synchronization. It is not a Dify tool, an assistant session API, or an authority to modify MES data.

`POST /ai-internal/knowledge-export/order-chain`

Header: `Authorization: Bearer <separately provisioned maintenance credential>`.

The only accepted body is `{"orderPk":"1"}`. `orderPk` is a positive canonical decimal primary key representable by Java `long`; numbers, leading zeros, arbitrary source/table/SQL fields, roles, scope claims, and pagination fields are rejected. The server independently checks a configured order allowlist. Ordinary MES credentials and assistant run tokens confer no access.

## Configuration and activation

The independent gate `assistant.v2.graph-export.enabled` defaults to false when absent. It does **not** require `assistant.v2.enabled`, and does not switch the original assistant. Enabling requires both:

- `assistant.v2.graph-export.bearer-key`: a separately generated private random secret, 32–256 URL-safe characters (`A-Z`, `a-z`, `0-9`, `_`, `-`). There is no default credential and no logging of credentials.
- `assistant.v2.graph-export.allowed-order-ids`: a nonempty comma-separated list of positive canonical primary keys. Wildcards are rejected.

Use private configuration outside the repository; do not reuse a Dify, MES session, or graph retrieval key. Restrict network exposure to the maintenance client. Deployment configuration and production activation are not part of this change. No secret-bearing configuration file is added. Only this module's mapper package is registered when enabled.

## Response shape

```json
{
  "schemaVersion": "1",
  "exportId": "UUID",
  "scope": {"orderPk": "1"},
  "sourceObservedAt": "2026-11-08T00:00:00Z",
  "readFinishedAt": "2026-11-08T00:00:01Z",
  "complete": true,
  "nodes": [
    {"id":"Order:1","kind":"Order","source_table":"customer_order","source_id":"1","properties":{"status":"APPROVED"}}
  ],
  "edges": [],
  "missingLinks": [
    {"source":"Order:1","type":"HAS_ITEM","reason":"NO_EXACT_LINK_IN_SCOPE"}
  ]
}
```

Node IDs are `<kind>:<source primary key>`; all IDs and `scope.orderPk` are strings. Each edge has `id`, `source`, `target`, `type`, `source_table`, `source_id`, and `reliability`. Edge IDs are `<type>:<source_table>:<source_id>`, deterministic across retries. Only `CONFIRMED` edges are emitted in this slice; insufficient or conflicting links are omitted. `CANDIDATE` is reserved for a future reviewed exporter, not inferred here.

| Kind | Source table | Whitelisted properties |
|---|---|---|
| Order | customer_order | status (audit_status) |
| OrderItem | customer_order_item | status (item_status), quantity |
| Plan | production_plan | status (plan_status) |
| WorkOrder | work_order | status, planned_quantity, completed_quantity |
| Inspection | quality_inspection | status (inspection_result), sample_quantity, qualified_quantity, unqualified_quantity |

Numeric quantities remain JSON numbers. Null values are omitted, not replaced with zero. Status values are emitted only when uppercase code-shaped (`[A-Z_]{1,32}`). There are no customer names, contacts, user/inspector identifiers, product names, arbitrary remarks, prices, free text, or raw rows.

| Relation | Source → target | Source record supporting relation |
|---|---|---|
| HAS_ITEM | Order → OrderItem | customer_order_item.order_id |
| PLANNED_AS | OrderItem → Plan | production_plan_item.order_item_id and plan_id; edge source_id is plan_item_id |
| HAS_WORK_ORDER | Plan → WorkOrder | work_order.plan_id and plan_item_id, with plan_item_id resolving to an order item owned by requested order and matching plan_id |
| HAS_INSPECTION | WorkOrder → Inspection | quality_inspection.work_order_id, reached only through the same exact order-item/plan/work-order join |

All mappings were checked against `sql/init/init.sql` and the matching current entities/mappers. `production_plan.source_order_id` may admit an otherwise unlinked Plan node, but never invents an OrderItem relation. Plans with order items from multiple orders do not expand to other orders' work orders. Same material, batch label, or name never establishes identity. An unbound or conflicting work order on an allowed plan yields a plan-level diagnostic without exporting that work order's identity or content.

**Importer requirement:** bind every imported node and edge to the exported `scope.orderPk` and the chosen graph generation. Shared stable Plan IDs must not allow cross-order traversal after multiple exports are merged. Scope must be checked on both endpoints and the edge. Export completion is not query authorization.

## Snapshot, budgets, and missing links

Seven fixed SELECT projections execute in one Spring `@Transactional(readOnly=true, isolation=REPEATABLE_READ, timeout=30)` method. Each SQL statement has a 10-second query timeout and `LIMIT 1000`; SQL arguments bind only the authorized order primary key. No business service, schema bootstrap, procedure, write mapper, external model, or graph driver is invoked.

`sourceObservedAt` and `readFinishedAt` bracket the source read using UTC application time. With transactional InnoDB tables and the configured Spring transaction manager, the repeatable-read transaction provides the export's consistent source view. This is not a historical snapshot identifier or a claim that graph synchronization is current. No database was contacted during implementation verification.

Any projection reaching 1000 rows conservatively sets `complete=false` and adds `ROW_LIMIT_REACHED`. Exactly 1000 and more-than-1000 cannot be distinguished within the strict fetch bound. Consumers must not mark this export READY as a complete replacement; there is no continuation cursor. Required parents absent from exported projections cause descendants/edges to be omitted, with `PARENT_OUTSIDE_EXPORTED_PROJECTION` and `complete=false`. No dangling edges are emitted.

`complete=true` means extraction completed within bounds, **not** that every expected business link exists. `missingLinks` can contain:

- `NO_EXACT_LINK_IN_SCOPE`: no exact downstream edge was present in the allowed projection.
- `NO_EXACT_ORDER_ITEM_LINK`: a scoped Plan lacks an exact exported OrderItem relation.
- `UNBOUND_OR_CONFLICTING_WORK_ORDER`: a scoped plan has an unbound work order or conflicting plan reference, deliberately omitted.
- `ROW_LIMIT_REACHED`: `type` names the bounded source projection; root Order is `source`.
- `PARENT_OUTSIDE_EXPORTED_PROJECTION`: `type` identifies the omitted relation; root Order is `source`.

An unlinked inspection cannot be attributed to an order merely because material matches. This exporter does not enumerate unrelated or unbound inspections, shipments, inventory movements, process steps, equipment, or aftersales records. Future graph trace responses must retain these coverage limits.

## Failure behavior

Missing/invalid maintenance credential → 401. Valid credential with an unlisted order → 403. Unsupported or malformed body/order ID → 400. Authorized order absent → 404. Source/transaction failures → generic 503 with no SQL, connection strings, or source exception content. No partial JSON export is returned on database failure. Authorization occurs before service reads. In disabled mode, the controller/service/dedicated mapper are absent.
