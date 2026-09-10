# BUSINESS diagnostic contract (v1)

This changes diagnostics only. HTTP requests, payloads, SQL, assertions, transaction semantics,
credentials, time budgets and file writes retain their prior behavior. Do not infer completion
or absence of earlier writes from a failed step. No automatic retry is introduced.

Success remains `P6_BUSINESS_STATUS=PASS` with exit 0. Failure is exactly one ASCII JSON record
with exit 1. The runner accepts at most 1024 characters (including an optional final newline),
captures at most 4096 stdout characters in memory, and never persists raw stdout/stderr.
Other tool output limits and original-handle cleanup remain in force.

Example (synthetic, no identity or credential fields):

```json
{"SchemaVersion":1,"Status":"FAIL","Code":"REHEARSAL_BUSINESS_ASSERTION_FAILED","Step":"LOGIN","ExceptionKind":"ASSERTION","HttpStatus":403,"SqlState":"NONE","AssertionId":"B010"}
```

Only these eight fields, in this canonical order, are accepted. Unknown/duplicate fields,
unknown values, multiple records, truncation, oversized output and exit/result disagreement
are rejected as `REHEARSAL_BUSINESS_DIAGNOSTIC_INVALID`. The report retains a fixed rejection
code, never the rejected text. Valid failures are saved under `Evidence[].BusinessFailure`
and their `Code` is also retained on that evidence item and propagated to the caller.
Cleanup errors may still override the top-level failure; the nested business evidence remains.

- `Step`: a compile-time stage enum. Includes ownership/environment checks, login/password
  rotation/relogin, vehicle/terminal creation and binding, capability verification, system
  lookup, preview snapshots/comparison, configuration application, count checks, output-file
  writes and lease release. Repeated entities use the same name; IDs and URLs are not emitted.
- `ExceptionKind`: ASSERTION, SQL, JSON, TIMEOUT, IO, ARGUMENT or OTHER. No runtime class name,
  exception message, stack trace, request/response body, SQL text or cause text is serialized.
- `HttpStatus`: 0 if unavailable, otherwise 100–599 for the current step. Reset at each step
  so an earlier successful HTTP response is not attributed to a later SQL/file failure.
- `SqlState`: NONE outside SQL errors, an explicitly enumerated PostgreSQL SQLSTATE, or OTHER
  for an unrecognized value. Arbitrary five-character strings are not accepted. Cause traversal
  is bounded to eight distinct exceptions. Java and runner maintain independent allowlists.
- `AssertionId`: NONE or the fixed identifier below. Assertions preserve the original
  `REHEARSAL_BUSINESS_ASSERTION_FAILED` code; all other helper exceptions preserve
  `REHEARSAL_BUSINESS_FAILED`.

| ID | Check |
| --- | --- |
| B001 | No command-line arguments |
| B002–B004 | Run identity, path chain, owner marker |
| B005–B006 | Port and database-credential shape |
| B007 | Fixed API path boundary |
| B008–B009 | Reserved, not currently emitted |
| B010–B012 | HTTP status/body bound, empty 204 body, data envelope |
| B013–B015 | Credential rotation inputs, login token, relogin token shape |
| B016–B017 | Terminal/system version |
| B018 | Preview leaves snapshots unchanged |
| B019 | Final entity counts |
| B020–B021 | Lease query row and deadline |

## Tests without rehearsal

Run with Windows PowerShell 5.1:

```powershell
powershell.exe -NoProfile -File tools/ops-safety/tests/p6-composite-business-diagnostics.tests.ps1
powershell.exe -NoProfile -File tools/ops-safety/tests/p6-composite-business-result.tests.ps1
```

The first uses the existing built API jar only to obtain Jackson dependencies. It compiles
fresh helper/test classes in a new private synthetic directory, launches the real helper
main/prepare flow against a local test HTTP server and test-only JDBC driver, and verifies
the helper-to-runner diagnostic path. It does not start the API or PostgreSQL. Synthetic
test directories are retained for inspection, separately from all historical rehearsal runs.
The second uses bounded real child processes to test accepted/rejected outputs, evidence
serialization, stderr suppression, error-code propagation and process cleanup.

These are contract tests, not a full local rehearsal or proof of the previous BUSINESS root cause.
