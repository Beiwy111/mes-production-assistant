# Stage 4 Java export verification

Date: 2026-11-08. Scope: independent maintenance-only order-chain exporter. No existing Java business classes, controllers, Maven dependency definitions, or old assistant routes were changed.

Test-first sequence:

1. Added five `GraphExport*Test` classes before production implementation. Ran `mvn -Pquality '-Dtest=GraphExport*Test' test`; compilation failed because the new GraphExport classes/packages did not exist. This was a missing-feature compile red, not a completed assertion run.
2. Added the narrow exporter/configuration/access/controller/mapper and response records.
3. Ran the same targeted command. Initial suite passed 16 tests.
4. Final review found a diagnostic gap for work orders with a null header `plan_id` and an explicit in-scope `plan_item_id`. Added `missingPlanHeaderReferenceIsDiagnosedThroughTheExplicitPlanItem`; ran `mvn -Pquality '-Dtest=GraphExportMapperTest' test` and observed **4 tests, 1 assertion failure, 0 errors** because the diagnostic query had no plan-item anchor.
5. Corrected only the diagnostic SELECT to follow the explicit plan-item anchor and report null/conflicting plan references. Re-ran `mvn -Pquality '-Dtest=GraphExport*Test' test`. Final Maven run finished at 2026-11-08 16:18:49 +08:00 with `BUILD SUCCESS`: **17 tests, 0 failures, 0 errors, 0 skipped**.

| Test class | Tests | Coverage |
|---|---:|---|
| GraphExportAccessTest | 3 | dedicated credential, ordinary token rejection, configured order allowlist, invalid/overflowing IDs, missing/weak credential, wildcard/empty scope |
| GraphExportConfigurationTest | 3 | absent/default-off gate, assistant gate independence, missing credential startup failure, dedicated MyBatis mapper registration with mock DataSource and no connection |
| GraphExportControllerTest | 3 | actual HTTP handler via MockMvc, unknown fields/role/SQL rejection, non-string/invalid body, 401/403 with zero mapper interactions, JSON contract, sanitized 503 |
| GraphExportMapperTest | 4 | actual MyBatis mapped-statement parsing, SELECT command type, bound scope parameters, hard limits, no private columns, exact owner/plan joins, missing-plan diagnostics, transaction declaration |
| GraphExportServiceTest | 4 | safe projections, deterministic provenance edges, complete exact chain, omitted ambiguous links, row cap/incomplete signal, no dangling edges/descendants, absent order 404 |

The mapper tests parse actual annotated SQL with MyBatis; they do not execute MySQL query plans or prove live schema/data consistency. The conditional registration test uses a mock DataSource and verifies no access to it. Mapper responses in service/HTTP tests are isolated fixtures. The transaction test verifies the declaration, not an actual server transaction or isolation implementation.

Observed warnings: existing AndonAlarmMapper deprecation/compiler notices and JVM mock instrumentation warning; the intentional invalid-configuration test logs expected Spring context failure and duplicate-mock mapper registration warnings. No credentials or live database values were emitted.

Only the requested targeted suite was executed for this delegated slice; the coordinating agent owns aggregate regression verification. No MES process was started/restarted, no database was contacted, no live environment secret was read, and no service/graph deployment, commit, or push was performed.

Limits: strict 1000-row per-projection cap is conservative at exactly 1000; no pagination; export allowlist is maintenance scope, not seven-agent end-user authorization; only the five documented node kinds/four edge types; no material-only inference; missing business links remain explicitly absent. Production source transaction behavior and isolated real-MySQL export remain unverified.
