# ADR-004: Execution status and rule truth are separate; missing evidence never yields PASS

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

A test result answers two different questions:

1. Did the test infrastructure do its job? (Could StateVerdict arm faults, trigger the workflow,
   and observe the expected deliveries?)
2. Did the business rule hold? (According to committed state, not logs or HTTP status codes.)

A single PASS/FAIL flag mixes them up. Two examples of what goes wrong:

- The payment service never exposes its captures. A naive tool sees "no violation found" and
  reports PASS. In reality nothing was verified.
- StateVerdict observes two captures for one order, then the reporting step crashes. A naive tool
  reports ERROR, and a real, evidenced double charge disappears.

## Decision

Each run records:

- `ExecutionStatus`: `COMPLETED`, `TIMED_OUT` (expected deliveries not all observed before the
  deadline) or `INFRA_ERROR` (StateVerdict could not drive the scenario).
- One `EvaluationStatus` per rule: `SATISFIED`, `VIOLATED`, or `UNKNOWN` (evidence missing or
  unreadable).

The developer-facing `Verdict` is derived by a fixed precedence (`Verdict.derive`):

| Precedence | Condition | Verdict | Exit code |
|---|---|---|---|
| 1 | any rule `VIOLATED` | **FAIL** | 1 |
| 2 | execution `INFRA_ERROR` | **ERROR** | 3 |
| 3 | execution `TIMED_OUT`, any rule `UNKNOWN`, or no rules evaluated | **INCONCLUSIVE** | 2 |
| 4 | execution `COMPLETED` and every rule `SATISFIED` | **PASS** | 0 |

Rules are pure functions of an `EvidenceBundle`: no I/O, no clock. Every evidence record carries
its source, observation time and the SHA-256 of the exact response body.

## Consequences

- A violation observed after a timeout or before an infrastructure failure is still a FAIL.
- CI gates should treat 1, 2 and 3 as blocking for critical rules (spec section 8): an
  INCONCLUSIVE result means "not verified", not "probably fine".
- Because rules are pure and the report contains the full evidence, `stateverdict replay-evidence`
  re-derives the same verdict from a saved report without contacting any service (spec ADR-005).
  Replay reproduces the *evaluation*, not the original distributed execution.
