# Conversational order intake verification — 2026-11-14

## Gateway boundary

`POST /assistant-workbench/order-intake` accepts only `{text,context}`. Text is limited to 2,000 characters; context is a JSON-object string limited to 16,000 characters. The intake-only byte budget accommodates Chinese text; existing request limits remain unchanged.

Live MES `/auth/userInfo` establishes identity. A new fixed read-only SQL query checks current user/role status and an ADMIN/ORDER/CUSTOMER role. Existing gateway status, reads, streams, and cancellation retain the separate ADMIN-only authorization predicate.

The same configured SUP API key invokes blocking Dify intake. No stage5 run is created or observed. Output is validated against the exact action/field/clearFields/question contract, including quantity type, date validity, size limits, duplicate keys, unknown fields, and conflicting clears. There are no model-selected material IDs, permissions, owners, or approval fields.

## SUP workflow

The optional `order_intake` input routes before the existing read branch. Intake executes:

1. Bounded input validation.
2. `intake_intent_llm`: SUP intent classification using the existing DeepSeek provider/model.
3. Intent schema validation.
4. `intake_llm`: ORDER extraction of explicitly changed fields from original text/context.
5. Patch validation, including consistency with SUP's action.
6. `order_intake_json` output.

Both LLM nodes execute for successful ORDER, QUERY, and CANCEL intake. QUERY/CANCEL extraction returns empty patches. Natural confirmation is an ORDER follow-up with an empty patch and a request to use the confirmation button. This branch performs no order submission.

The final published SUP version is `2026-11-14 07:15:57.303915`, with 19 nodes and `graph_matches_local=true`. No other app was deployed and no new API key was created. Regeneration via `build_stage5.py --role SUP` is byte-identical. Existing read-node definitions and main calls were compared against the actual predeployment graph and are unchanged, apart from adding unused shared validation functions to embedded code.

Final prompt hardening restricts product search to 120 characters, receiver/customer names to 80, phone to 30, and remark/address to 500, all within the backend and gateway bounds. Python validates the actual string length, including padding. Explicit but ambiguous changes clear the old field before clarification. Multi-model requests clear product selection (and ambiguous quantity), ask the user to choose one model, and never choose a model or sum quantities silently. These changes require no Java change or gateway restart.

## Evidence

- `python -m pytest backend/assistant/agents/tests -q`: 30 passed.
- `mvn -o -f backend/assistant/rag/data/intake-build/pom.xml -Dtest=WorkbenchGatewayServerTest,WorkbenchHttpServicesTest,WorkbenchRuntimeMainTest test -q`: 30 passed (21 gateway, 8 HTTP adapter, 1 mapper/runtime). Compilation output is isolated from live classes.
- `python backend/assistant/rag/data/intake-live-check.py`: eight live cases passed after targeted retries: 100 as quantity, explicit address plus tomorrow, unrelated inventory query, draft cancellation, natural confirmation, clearing an ambiguous date on a READY draft, and multiple-model requests on both new and existing drafts. Two baseline cases initially failed with DeepSeek connection read timeouts and passed when retried sequentially; the local result retains those first-attempt errors. Successful final responses took 3.6–6.4 seconds.
- Read-only inspection of Dify execution records for all eight final successful runs confirmed exactly the eight intake-path nodes, including both LLMs, and no read-runtime or tool nodes.

Private local artifacts under ignored `backend/assistant/rag/data/`:

- `sup-before-order-intake-2026-11-14.json`: sanitized original published graph, without environment-variable values.
- `order-intake-live-2026-11-14.json`: synthetic live fixture outputs and run IDs.
- `order-intake-before-hardening-2026-11-14.json`: reproduced ambiguous-date and multiple-model failures before prompt hardening.
- `order-intake-node-traces-2026-11-14.json`: actual node IDs for those eight successful runs.

This task did not restart the MES backend or standalone gateway. Gateway deployment and the end-to-end existing inventory-query regression are coordinated by the integrating task.
