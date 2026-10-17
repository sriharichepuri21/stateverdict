# StateVerdict

**Fault exploration and evidence-based correctness testing for event-driven microservices.**

Every service in a checkout flow can return `200 OK` while the business outcome is still wrong: an
order confirmed after the payment was declined, or one purchase charged twice because a message was
delivered twice. StateVerdict makes specific delivery faults happen on demand, judges the outcome from
each service's **committed state** (not logs, traces or status codes), and reports one of four
verdicts: **PASS**, **FAIL**, **INCONCLUSIVE** or **ERROR**.

## What it found

The example payment service has two handlers: a deliberately naive one and an idempotent fix.
StateVerdict delivered the same `payment.requested` event twice and checked rule **SV-002: at most one
committed payment capture per order**. 10 clean-state trials per row, measured in CI:

| Payment handler | Fault | PASS | FAIL | INCONCLUSIVE | ERROR |
|---|---|---|---|---|---|
| naive | none (negative control) | 10 | 0 | 0 | 0 |
| naive | event delivered twice | 0 | **10** | 0 | 0 |
| idempotent | none (negative control) | 10 | 0 | 0 | 0 |
| idempotent | event delivered twice | **10** | 0 | 0 | 0 |

With the fix, the event is still delivered twice, but only one capture is committed. StateVerdict
tells a harmless duplicate *delivery* apart from a harmful duplicate *side effect*. Method, CI runs and
limits: [docs/results/spike-reproduction.md](docs/results/spike-reproduction.md).

Real output of the failing run (from the CI demo job):

```text
StateVerdict v0.1.0

Scenario:  checkout-duplicate-payment-request (v1)
Run:       sv-cf886a25
Faults:    {type=DUPLICATE, topic=payment.requested, copies=2}
Execution: COMPLETED

Rule SV-002 v1
  At most one committed payment capture per order

  Expected: at most 1 capture
  Observed: 2 captures
  Result:   VIOLATED
  Why:      Order 949b5491-4492-43b5-a811-3ce47002d0c2 was captured 2 times; captured event ids: aced970e-46c0-4c22-82a1-aa99607eaa77, aced970e-46c0-4c22-82a1-aa99607eaa77.
  Evidence: payment.captures

Overall verdict: FAIL
Report:          artifacts/demo-duplicate-naive.json
Exit code:       1
```

## Try it: one command

You need Docker (with Compose) and curl. Nothing else, not even Java. A GitHub Codespace works
out of the box (Code → Codespaces → Create).

```bash
scripts/demo.sh
```

It builds everything, starts PostgreSQL, Kafka and both services, and runs four checks:

```text
1. baseline (naive handler)            expected PASS  got PASS         ok
2. duplicate (naive handler)           expected FAIL  got FAIL         ok
3. replay of step 2's evidence         expected FAIL  got FAIL         ok
4. duplicate (idempotent handler)      expected PASS  got PASS         ok

DEMO OK: the fault is detected against the naive handler and accepted after the fix.
```

It exits non-zero if any step gives an unexpected verdict, and CI runs it on every pull request. Use
`KEEP=1 scripts/demo.sh` to leave the stack running afterwards.

## Schedules: controlling the order of events

Some bugs need events and user actions to happen in a particular order. A **schedule** is a JSON list
of steps that StateVerdict runs across both services: arm a fault, place an order, wait until an
event is held, cancel, release. Example: hold `payment.completed`, let the customer cancel while it
is held, then release it. Against the naive order handler the cancelled order ends **CONFIRMED**
(rule SV-003 fails, 10/10 trials); against the guarded handler it stays cancelled (10/10 PASS).

```bash
# with the stack running (KEEP=1 scripts/demo.sh)
docker compose -f infrastructure/compose/docker-compose.yml --profile cli run --rm stateverdict \
  run-schedule scenarios/cancel-while-payment-completion-held.json --trials 10
```

See [scenarios/](scenarios/README.md) for the step format and examples.

## Exploration: finding schedules nobody wrote

`stateverdict explore` generates random but valid schedules from a seed (faults on either event,
customer cancels at different moments, approved or declined amounts), runs each once, and saves
every schedule that violates a rule as a file you can rerun. Nobody tells it where the bugs are.

```bash
docker compose -f infrastructure/compose/docker-compose.yml --profile cli run --rm stateverdict \
  explore --iterations 60 --seed 42
```

The same seed always generates the same schedules. A finding from one run is a lead; confirm it
with `run-schedule <saved file> --trials 10`.

Seed 42, 60 schedules: against the naive handlers it found **24 violations of SV-002 and SV-003**, only
3 of them the race written by hand; 4 needed no fault at all, just a customer cancelling quickly.
Against the fixed handlers all 60 passed. Details: [docs/results/exploration.md](docs/results/exploration.md).

## Shrinking: the smallest schedule that still fails

`stateverdict shrink <schedule>` cuts a failing schedule down: it tries removing each fault, the
customer's cancel, and simplifying parameters, keeps a change only if the smaller schedule still
fails **every one** of K runs (default 3), and repeats until nothing more can go. Removing a hold also
removes its wait and release, so every candidate stays valid. The original and the result are each
measured over N runs (default 10), so you see whether the smaller schedule is as reliable as the
original. A finding that does not fail every time is measured and reported, not shrunk.

```bash
docker compose -f infrastructure/compose/docker-compose.yml --profile cli run --rm stateverdict \
  shrink artifacts/found/<finding>.json
```

Measured in CI: the most complex SV-002 finding shrank to "deliver the payment request twice, place
the order", and the most complex SV-003 finding shrank to just **"place the order, cancel it"**, both
failing 10/10. The cancel/confirm race never needed a fault. Details:
[docs/results/shrinking.md](docs/results/shrinking.md).

## Why not just write JUnit tests?

Measured on a corpus of 6 deliberate defects and 3 harmless changes
([docs/evaluation/results.md](docs/evaluation/results.md)), with predictions written down first:

| | Defects caught | False alarms |
|---|---|---|
| The services' 32 JUnit + Testcontainers tests | **6 / 6** | 1 / 3 |
| StateVerdict (3 business rules, generated schedules) | 4 / 6 | **0 / 3** |

The JUnit suite was written by the same person who wrote the defects, so it was aimed at them. It also
raised a false alarm on a harmless change because three tests assert implementation details.
StateVerdict knew nothing about the code; it missed one concurrency defect (its dispatcher delivers
duplicates sequentially) and one defect no rule covered. They are complementary: targeted tests for
known behaviour, generated schedules plus business rules for the combinations nobody wrote a test for.

## How it works

```text
                 X-Correlation-Id: <run id>
StateVerdict ──► order-service ──► Kafka: payment.requested ──► payment-service
     │                                                              │
     │  arm fault for the run                    listener ──► Dispatcher ──► capture handler
     ├────────────────────────────────────────────────────────────►│ (hold / delay / duplicate,
     │                                                              │  journal every delivery)
     │  read journal + committed captures (evidence)               │
     ◄──────────────────────────────────────────────────────────────┘
     │
     └─► evaluate rules on frozen evidence ──► verdict + JSON report + exit code
```

1. **Arm a fault** for one run in the payment service's test-mode dispatcher.
2. **Trigger** the workflow: place an order tagged with the run id.
3. **Wait** until the dispatcher journal shows every expected delivery (deadline-bounded).
4. **Collect evidence** from committed-state APIs. Each record keeps its source, observation time and a
   SHA-256 of the exact response.
5. **Evaluate** rules as pure functions of that evidence and derive the verdict.

## Design decisions

| Decision | Why | Record |
|---|---|---|
| Faults are injected in a dispatcher *after* Kafka consumption | Exact, per-event control; a network proxy cannot duplicate one specific event | [ADR-002](docs/decisions/ADR-002-controlled-dispatch-after-consumption.md) |
| Execution status is separate from rule truth | A real violation is never erased by a later infrastructure error; missing evidence is never a PASS | [ADR-004](docs/decisions/ADR-004-execution-and-rule-truth-are-separate.md) |
| The fix uses an inbox table in the same transaction | Safe under concurrent copies, unlike a "check, then insert" | [ADR-006](docs/decisions/ADR-006-idempotency-with-an-inbox-table.md) |
| The tool is a modular monolith; the system under test is distributed | Distributed complexity belongs where it is being tested | [ADR-001](docs/decisions/ADR-001-modular-monolith-core.md) |

Verdict precedence: any rule **VIOLATED** → FAIL (exit 1); otherwise infrastructure failure → ERROR
(exit 3); otherwise timeout or missing evidence → INCONCLUSIVE (exit 2); otherwise PASS (exit 0).

## Repository

| Path | What it is |
|---|---|
| `apps/stateverdict-core/` | Runner, evidence, rules, verdicts, replay, CLI (plain Java) |
| `libs/stateverdict-dispatch/` | Test-mode dispatcher: hold, delay, duplicate, journal |
| `examples/commerce/` | System under test: order and payment services (Spring Boot 4.1, Java 21) |
| `infrastructure/compose/` | PostgreSQL 17, Kafka 4.1 (KRaft), services, CLI |
| `scripts/` | One-command demo, measurement and smoke scripts |
| `docs/` | ADRs, event contract, measured results, retrospective, full specification |

## Using the CLI directly

With the stack running (`KEEP=1 scripts/demo.sh`, or `docker compose -f infrastructure/compose/docker-compose.yml up --build -d`):

```bash
SV="docker compose -f infrastructure/compose/docker-compose.yml --profile cli run --rm stateverdict"

$SV run checkout-duplicate-payment-request --copies 1        # baseline
$SV run checkout-duplicate-payment-request --copies 2 --report artifacts/dup.json
$SV replay-evidence artifacts/dup.json                       # same verdict, no services contacted
$SV run checkout-duplicate-payment-request --copies 2 --trials 10
```

Developing locally with Java 21 and Maven: `mvn -B verify` runs all 142 tests, using Testcontainers
for PostgreSQL and Kafka.

## Status and limits

The seven-step technical spike from the [specification](docs/specification/StateVerdict_Engineering_Specification_v1.0.pdf)
is complete; see the [retrospective](docs/results/spike-retrospective.md). Since then: a second event
flow with customer cancellation, schedules, rule SV-003, the explorer (gate 2 met) and the shrinker
(gate 3 met). StateVerdict covers two defect classes (duplicate side effects, and an outcome
overwriting a cancellation), with sequential delivery on a single-partition topology. Gate 4 has a
measured head-to-head with the conventional suite. Next: SV-004 (refund for a cancelled, captured
order), which would catch M04, and a concurrent-delivery intervention, which would catch M03.

## Guiding principle

Never report a verified business outcome without sufficient, trustworthy evidence.
Missing evidence produces **INCONCLUSIVE**, never **PASS**.
