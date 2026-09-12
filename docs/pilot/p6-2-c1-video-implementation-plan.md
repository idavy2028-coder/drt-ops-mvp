# C1 declaration implementation plan — 2026-09-12

Goal: authenticated 0x1003 → durable gateway outbox → API observation + device VIDEO DECLARED, isolated only.
Architecture: strict shared codec; independent CAPABILITY_DECLARATION; atomic observation/fact/audit; no automatic VERIFIED.
Constraints: no real devices/cloud DB/manifest/push/deploy. API V22 and gateway V6 are separate Flyway histories; never edit V21 or earlier migrations.

1. Verify local Docker readiness and build the saved V21-compatible baseline from a minimal context. Record base/image/JAR hashes; no business network containers.
2. Migration recovery: before migration stop writers and capture isolated API V21 pg_dump custom backup plus gateway V5 offline database backup. Record hashes and Flyway history. On failure stop only owned instances, retain failed evidence, restore backup into a NEW isolated database/path, point only the local harness to restored V21/V5 and matching old binaries; verify history and row counts. Never delete Flyway rows or clean shared databases. Rehearse restore before calling rollback proven.
3. Add tests for malformed/valid declaration and durable dispatch, then strict codec/dispatch. Extend gateway V6 CHECK and high-priority selection. Identity comes from live session, no role circular dependency.
4. Add API V22 observation table and service tests for device association, idempotency, DECLARED, zero video, disabled/verified conflicts, lease mismatch and audit rollback. Integrate internal ingress router; preserve capability verification API.
5. Test both migrations against isolated DBs, replay/rollback and HTTP/TCP simulator path. Build final changed gateway and record artifact hashes. A remains blocked on actual media verification.

Protocol source: published JT/T 1078—2016 reproduction (43 pages), section5.3.3/table11–12; third-party hosted PDF, not issuer-hosted. Do not infer unsupported vendor fields.
Review gate: stop on platform failures, record evidence; do not silently bypass failed tests.
