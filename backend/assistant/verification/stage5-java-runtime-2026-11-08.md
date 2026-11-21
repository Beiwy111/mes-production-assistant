# Stage 5 Java runtime verification — 2026-11-08

Scope: new independent runtime classes/tests and stage5 runtime contract only. Existing MES classes, E0–E5 services, mapper, POM, Spring configuration and launch settings were not edited. No commit, MES startup, assistant enablement, live JDBC connection or database write was performed by this implementation task.

## Test-first record

1. Wrote nine real HTTP scenarios using the existing E0–E5 service/registry and a mocked external mapper. Initial run `mvnw.cmd -Pquality -Dtest=Stage5RuntimeServerTest test -q` failed with **9 assertion failures, 0 errors**: the independent runtime class was absent.
2. Wrote explicit session lifecycle tests. Initial run `mvnw.cmd -Pquality -Dtest=ReadOnlyDeliveryAccessTest test -q` failed with **3 assertion failures, 0 errors**: the read-only operation adapter was absent.
3. Wrote isolated factory verification. Initial run `mvnw.cmd -Pquality -Dtest=Stage5RuntimeMainTest test -q` failed with **1 assertion failure, 0 errors**: the independent entry/factory was absent.
4. Implemented runtime, adapter and main/factory. Combined targeted command below exited **0** with **13 tests, 0 failures, 0 errors, 0 skipped**. Removed the initial reflection scaffolding in favor of ordinary typed test construction and reran the same targeted command.

```powershell
cd D:\soft\idea\computer\backend
.\mvnw.cmd -Pquality '-Dtest=Stage5RuntimeServerTest,ReadOnlyDeliveryAccessTest,Stage5RuntimeMainTest' test -q
```

The JVM emits its existing Mockito/CDS boot-classpath sharing warning; no test failure was hidden. Surefire report files are under `backend/target/surefire-reports/com.upc.computer.assistant.v2.runtime.*.txt`.

## Behaviors verified

- Missing/incorrect-role credentials and non-allowlisted orders fail before database reads.
- Only strict typed JSON is accepted; duplicate keys, extra role/scope/result/fact fields, query strings, oversized summaries and forged result references are rejected.
- Core experts require actual server-registered upstream completion receipts. Roles cannot use sibling routes; another run cannot satisfy a missing upstream task.
- Concurrent duplicate ORDER begin requests share one task and one E1/E4 read pair. Identical completion repeats preserve the receipt; changed text conflicts.
- Unsupported experts return empty business evidence and `UNSUPPORTED_BUSINESS_SOURCE`.
- Missing core tasks produce `INCOMPLETE`; model promises/quantities do not affect deterministic final facts or verdict. Unknown quantities remain null and current partial evidence yields `INSUFFICIENT_EVIDENCE`.
- E5 detects changed shipment records, returns `DATA_CHANGED`, clears stale exposed fact collections and requires a fresh run.
- TTL, cancellation, capacity, sanitized read failures and failure-before-task-publication paths work.
- JDBC read-only/repeatable-read/manual-transaction settings precede mapper reads; both success and failure force rollback and session close; read-only refusal prevents mapper access.
- Factory registers exactly the one existing delivery mapper and 14 SELECT statements, with caching off and statement timeout 15 seconds, without opening a database connection.

## What this does not establish

The HTTP tests run an ephemeral loopback JDK server and real business calculations/registry over mocked mapper rows. Lifecycle tests use mocked JDBC/MyBatis resources. The factory test uses a deliberately unreachable local JDBC URL and never opens a connection. This is **not** a live MySQL account-grant test, real business-order verification, Dify deployment result or end-to-end seven-agent run.

The parent integration task owns the full backend quality suite, existing source-hash audit, actual read-only MySQL/runtime launch and Dify integration. Do not treat these 13 targeted tests as a claim that the full project suite or production integration passed.

The runtime assumes trusted local maintenance preview. Role keys apply to their role across allowed preview runs, while E query/result references and dependency state are server-bound to one run. It is not MES logged-in-user row-level authorization. Terminal finalization repeats return the original timestamped assessment until TTL; they do not silently make a new freshness claim.

## Integration correction: explicitly empty JDBC password

The integration task identified an existing local account configured with an empty password. The generic nonblank configuration check incorrectly rejected an explicitly supplied empty password. Added `explicitEmptyPasswordIsPreservedButMissingOrNullPasswordIsRejected`: the test first failed with one assertion failure and zero errors because `createFactory` threw `Missing private runtime configuration`. The password validation now distinguishes explicit empty from missing/null, preserves the exact configured value, and introduces no silent default. This factory-only regression never opens a database connection. The combined targeted command above then exited **0**: **14 tests, 0 failures, 0 errors, 0 skipped** (9 HTTP, 3 lifecycle and 2 factory tests).
