# Spike retrospective

- **Date:** 2026-10-16
- **Scope:** the seven-step technical spike in the engineering specification (section 11): prove that
  controlled fault injection plus committed-state evidence can detect, and then accept the fix for, a
  real cross-service correctness defect.
- **Result:** **done.** Every step is merged, and the whole proof reruns with one command
  (`scripts/demo.sh`) in CI on every pull request.

## The question the spike had to answer

> Can a tool make a specific delivery fault happen on demand, judge the outcome from committed state
> rather than logs or status codes, and tell a real defect apart from a harmless duplicate delivery,
> reproducibly?

**Yes, for one defect class, under the conditions below.**

## What was built

| Piece | Where | Decision record |
|---|---|---|
| Order and payment services, separate databases, Kafka 4.1 (KRaft) | `examples/commerce` | ADR-001 |
| Controlled dispatcher: hold, delay, duplicate, journal, reset | `libs/stateverdict-dispatch` | ADR-002 |
| Verdict model, evidence with provenance, rule SV-002, runner, replay, CLI | `apps/stateverdict-core` | ADR-004 |
| Repeated clean-state trials with outcome counts | `--trials`, `scripts/measure-reproduction.sh` | |
| Naive and idempotent capture handlers | `examples/commerce/payment-service` | ADR-006 |
| One-command reproduction | `scripts/demo.sh`, CI job `one-command-demo` | |

## Evidence

From [docs/results/spike-reproduction.md](spike-reproduction.md), 10 clean-state trials per row:

| Handler | Fault | PASS | FAIL | INCONCLUSIVE | ERROR |
|---|---|---|---|---|---|
| naive | none | 10 | 0 | 0 | 0 |
| naive | payment request delivered twice | 0 | 10 | 0 | 0 |
| idempotent | none | 10 | 0 | 0 | 0 |
| idempotent | payment request delivered twice | 10 | 0 | 0 | 0 |

Plus: replaying a saved FAIL report re-derives FAIL without contacting any service, and 81 automated
tests (unit, stub-server and Testcontainers) cover every verdict path.

## Against the specification's gates

| Gate | Status |
|---|---|
| 1. A manually defined scenario detects a genuine business-state violation | **Met.** SV-002, FAIL 10/10 against the naive handler; negative controls PASS |
| 2. A generated fault schedule finds a violation not written as a hand-made scenario | Not started during the spike; **met later** (PR #11, [exploration.md](exploration.md)) |
| 3. A failing schedule is reduced while preserving the violation under repeated trials | Not started during the spike; **met later** (PR #12, [shrinking.md](shrinking.md)) |
| 4. Negative controls, measured results and CI show the tool is dependable | Partly met during the spike; **met later** with a 6-defect, 3-control corpus and a measured comparison with the conventional suite ([evaluation/results.md](../evaluation/results.md)) |

## What went well

- **Separating execution from rule truth paid off immediately.** When CI started trials before the
  payment service was ready, StateVerdict reported ERROR 10/10 instead of a false PASS. The bug was in
  CI ordering, and the verdict model surfaced it exactly as designed.
- **Fault injection after consumption made reproduction exact.** Sequential duplicate delivery gave
  10/10 with no flakiness and no sleeps tuned by hand.
- **Testing against stub services** covered timeouts, unreadable state, unreachable services and replay
  in milliseconds, without Docker. Real-stack coverage stays in CI.
- **CI as the only place with Docker** forced everything to be scripted and reproducible from day one.

## What was harder or different than planned

- **A self-review found evidence contamination** in the first dispatcher version: a delivery still
  running during a reset wrote into the next run's journal. Fixed with a per-run generation counter.
  This is the class of bug the spec's isolation requirements warn about.
- **"Running" is not "ready".** Container orchestration readiness had to be made explicit
  (`scripts/wait-healthy.sh`).
- **Stacked pull requests** landed one change on a feature branch instead of `main`; fixed with a
  follow-up PR. Process now: one PR at a time from `main`.

## Known limits (what this does not show)

- **One defect class:** duplicate side effects from redelivery. No temporal rules, no ordering faults
  across topics, no compensation flows.
- **No real concurrency in the fault path:** the dispatcher delivers copies sequentially. The
  idempotent handler is separately tested with 8 concurrent copies, but StateVerdict cannot yet
  *inject* concurrent delivery.
- **Single partition, single consumer** topology (ADR-002).
- **The rule and the defect were written by the same person.** The negative controls reduce, but do not
  remove, the circularity concern. An independent mutation corpus and an external target application
  are the stronger answers.
- **10 trials per configuration** on one runner type: enough to detect a flaky setup, not to make
  claims about rare failures.
- **Scenarios and rules are Java code,** not the typed YAML format the spec describes.

## What comes next

In priority order, following the spec's phases:

1. **Mutation corpus and JUnit baseline (Gate 4):** a handful of seeded defects and valid changes, run
   through both StateVerdict and a hand-written Testcontainers/Awaitility test, with recall and
   false-positive counts.
2. **Generated fault schedules (Gate 2):** random but valid combinations of hold, delay and duplicate
   across more than one event, from a seed.
3. **Dependency-aware shrinking (Gate 3):** reduce a failing schedule and confirm the reduced one under
   repeated trials.
4. **A second rule class:** a bounded temporal rule (inventory released within N seconds of a rejected
   payment), which needs the inventory service.
