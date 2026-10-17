# Head-to-head results (gate 4)

- **Date:** 2026-10-17
- **CI run:** [39012932752](https://github.com/sriharichepuri21/stateverdict/actions/runs/39012932752) (`evaluation` workflow)
- **Corpus and predictions:** [corpus.md](corpus.md), written before this measurement
- **Per configuration:** `scripts/evaluate-config.sh`, one CI job each, in parallel

> **Update (PR #16):** concurrent duplicate delivery was added, and the evaluation reran (run
> [39056506247](https://github.com/sriharichepuri21/stateverdict/actions/runs/39056506247)).
> **M03 is now caught** (SV-002): StateVerdict stands at **6/6 defects, 0/3 false alarms**. See
> [After concurrent delivery](#after-concurrent-delivery).
>
> **Update (PR #15):** SV-004 and the "cancel after confirmed" customer behaviour were added, and the
> evaluation reran (run [39048156513](https://github.com/sriharichepuri21/stateverdict/actions/runs/39048156513)).
> **M04 is now caught** (SV-004), so StateVerdict stands at **5/6 defects, 0/3 false alarms**. See
> [After SV-004](#after-sv-004) below. The original measurement is kept as it was.

## What was compared

| | Conventional suite | StateVerdict |
|---|---|---|
| What runs | the 32 JUnit 5 + Testcontainers tests of the order and payment services | `explore --seed 42` (60 generated schedules) plus the 3 example schedules |
| What it knows | 32 hand-written tests, many aimed at specific behaviours | 3 business rules (SV-001, SV-002, SV-003); no knowledge of the code |
| "Detected" | at least one test fails | at least one run violates a rule |

For controls (harmless changes), "detected" is a false alarm.

## Result

| Config | Kind | Change | Conventional suite | StateVerdict |
|---|---|---|---|---|
| baseline | | none | quiet (0/32 failed) | quiet |
| C01 | control | extra read before the update | quiet | quiet |
| C02 | control | payment handler 300 ms slower | quiet | quiet |
| C03 | control | `payment.completed` published twice | **false alarm** (3/32 failed) | quiet |
| M01 | defect | idempotency removed | caught (4/32) | caught: SV-002 |
| M02 | defect | status guard removed | caught (1/32) | caught: SV-003 |
| M03 | defect | non-atomic idempotency (concurrent copies double-charge) | caught (1/32) | **missed** |
| M04 | defect | a CONFIRMED order can be cancelled | caught (1/32) | **missed** |
| M05 | defect | a declined payment confirms the order | caught (1/32) | caught: SV-001 |
| M06 | defect | capture write lost, CAPTURED still published | caught (4/32) | caught: SV-001 |

| | Defects caught | False alarms |
|---|---|---|
| Conventional suite | **6 / 6** | 1 / 3 |
| StateVerdict | 4 / 6 | **0 / 3** |

**All nine predictions in [corpus.md](corpus.md) held.**

## Reading the result honestly

- **The conventional suite won on detection, and it was set up to.** Its tests were written by the
  person who wrote the defects, several of them aimed at exactly these behaviours. Four of the six
  defects were caught by **one** targeted test each. A team's real suite would rarely be this well
  aimed, and the score says more about my test-writing than about JUnit.
- **StateVerdict caught four defects with three rules and no knowledge of the code.** It never saw
  the handlers; it generated schedules, read committed state and checked SV-001 to SV-003.
- **Its two misses are structural, and each one points at a specific next step.**
  - **M03 (concurrency):** the controlled dispatcher delivers duplicate copies one after another
    (ADR-002), so "two copies at the same moment" never happens. Only the hand-written
    8-concurrent-copies JUnit test can find it. Fix: a concurrent-delivery intervention.
  - **M04 (cancel after confirm):** no rule says a paid order cannot be cancelled, or that a
    cancelled paid order must be refunded. StateVerdict can only find violations of rules it has.
    Fix: SV-004, refund required for a cancelled, captured order.
- **The suite's false alarm is the typical cost of implementation-level assertions.** Publishing
  `payment.completed` twice is harmless (consumers must tolerate duplicates, and the order service
  does). Three tests fail because they assert *exactly one* event was published. StateVerdict checks
  business outcomes, so it is indifferent to how many messages it took to get there.
- **The two approaches are complementary.** Targeted tests are cheap and precise about known
  behaviours; generated schedules plus business rules cover combinations nobody wrote a test for
  (24 findings in [exploration.md](../results/exploration.md), only 3 of them the hand-written race).

## Found while building the harness

The first two evaluation runs produced wrong numbers, and they would have looked plausible:

1. **The mutations never reached the StateVerdict side.** Docker Compose only passes variables it is
   told about into containers, and `EVALUATION_MUTATION` was not listed. M03–M06 and C01–C03 ran
   against the unmodified fixed code, so M05 and M06 showed as "missed". **Fix:** pass the variable,
   and have every job refuse to measure until `printenv` inside both containers and the services' own
   startup logs confirm the configuration is active.
2. **Only 15 of 32 conventional tests ran for order-service mutations.** Maven stops after the first
   module with a failing test, so the payment-service tests never ran. **Fix:** `mvn -fae`.
3. **The new log check then failed every job**: `docker compose logs | grep -q` exits early, the
   writer gets SIGPIPE, and under `pipefail` that counts as a failure. **Fix:** read the logs into a
   variable before searching.

## After SV-004

PR #15 closed the M04 gap the way the original reading said it would: a refund flow in the fixed
services, a rule that checks it (**SV-004**: every capture of a cancelled order is refunded, a bounded
"eventually" rule re-observed for up to 5 s), and a fourth generated customer behaviour, *cancel after
the order is CONFIRMED*. The conventional suite grew to 37 tests with the refund tests.

| Config | Conventional suite | StateVerdict |
|---|---|---|
| baseline, C01, C02 | quiet | quiet |
| C03 | **false alarm** (3/37) | quiet |
| M01 | caught | caught: SV-002 |
| M02 | caught | caught: SV-003, SV-004 |
| M03 | caught | **missed** |
| M04 | caught | **caught: SV-004** (was missed) |
| M05 | caught | caught: SV-001 |
| M06 | caught | caught: SV-001 |

| | Defects caught | False alarms |
|---|---|---|
| Conventional suite (37 tests) | 6 / 6 | 1 / 3 |
| StateVerdict (4 rules, generated schedules) | **5 / 6** | **0 / 3** |

M04 was caught by generated schedules, not by a hand-written scenario aimed at it: no example schedule
was added for it. M03 (concurrency) remains the one structural miss.

## After concurrent delivery

PR #16 closed the last gap. The dispatcher gained `DUPLICATE_CONCURRENT`: the copies run on separate
threads and a start gate releases them together, so the handlers overlap. The generator can arm it
on either topic. The conventional suite gained one test (38) for the new control API type, which
runs against the naive handler and so does not change any detection.

| Config | Conventional suite | StateVerdict |
|---|---|---|
| baseline, C01, C02 | quiet | quiet |
| C03 | **false alarm** (3/38) | quiet |
| M01 | caught | caught: SV-002 |
| M02 | caught | caught: SV-003, SV-004 |
| M03 | caught | **caught: SV-002** (was missed) |
| M04 | caught | caught: SV-004 |
| M05 | caught | caught: SV-001 |
| M06 | caught | caught: SV-001 |

| | Defects caught | False alarms |
|---|---|---|
| Conventional suite (38 tests) | 6 / 6 | 1 / 3 |
| StateVerdict (4 rules, generated schedules) | **6 / 6** | **0 / 3** |

**Is it the concurrency that catches M03?** Measured against M03, 10 clean-state trials each:

| Schedule | Result |
|---|---|
| `duplicate-payment-request` (two copies, one after the other) | PASS 10/10 |
| `concurrent-duplicate-payment-request` (two copies at the same moment) | FAIL 10/10 |

Same message, same number of copies; only the timing differs. The shrinker agrees: given a generated
M03 finding, it reduced it to *ARM DUPLICATE_CONCURRENT x2 on payment.requested, place the order*.
Its last candidate, the same duplicate delivered sequentially, no longer failed, so it kept the
concurrency. That minimal schedule failed **8 of 10** confirmation runs. This is a real race, so
it is not certain. The hand-written schedule's 10/10 in the same job is a better estimate than the
shrinker's 8/10, but neither is 100% guaranteed.

### What this does and does not show

- M03 was caught by generated schedules. The prediction was revised to "detect" before this run
  ([corpus.md](corpus.md)), because the change was made specifically to close it. It is a closed
  gap, not a blind discovery.
- The race is modelled at the consumer, as two handler invocations at once. It is not two service
  instances in a consumer group competing for a partition (ADR-002).
- The conventional suite also catches M03, with a test written for it. The difference is that
  StateVerdict reached it without knowing the code, and raised no false alarm on C03.
