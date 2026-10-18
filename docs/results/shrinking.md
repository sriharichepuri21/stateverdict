# Shrinking results (gate 3)

- **Date:** 2026-10-16
- **CI run:** [39005252543](https://github.com/sriharichepuri21/stateverdict/actions/runs/39005252543)
- **Input:** findings from the seed-42 exploration against the naive handlers ([exploration.md](exploration.md))
- **Command:** `stateverdict shrink <finding> --rules <rule> --trials 3 --confirm 10` (`scripts/shrink-findings.sh`)

## Method

1. Run the finding 10 times. Shrink it only if it violates the target rule in **all 10** runs;
   otherwise report it as UNRELIABLE with its measured rate.
2. Try each candidate reduction, biggest first: remove a fault (with the wait and release that only
   existed for it), remove the customer's cancel, simplify a fault's parameters. Every candidate is a
   valid schedule.
3. Keep a candidate only if it violates the target rule in **all 3** of its runs (stop at the first
   passing run). Restart from the smaller schedule.
4. Stop when no single reduction still fails (1-minimal). Run the result 10 times.

## Result

| Finding | Original | Fails | Locally minimal | Fails | Candidates | Runs | Time |
|---|---|---|---|---|---|---|---|
| most complex SV-002 | 3 interventions | 10/10 | `ARM DUPLICATE payment.requested` → `PLACE_ORDER` | **10/10** | 5 | 29 | 62 s |
| most complex SV-003-only | 3 interventions | 10/10 | `PLACE_ORDER` → `CANCEL` | **10/10** | 3 | 27 | 68 s |
| SV-003 with no fault | `PLACE_ORDER` → `CANCEL` | 10/10 | (already minimal) | **10/10** | 1 | 21 | 45 s |

**Gate 3 is met:** each failing schedule was reduced while preserving the violation, confirmed over
10 runs.

## What shrinking revealed

- **The double charge needs exactly one thing:** the payment request delivered twice. Everything else
  in the generated schedule (a second fault, a cancel) was noise.
- **The cancel/confirm race needs no fault at all.** Both SV-003 findings shrank to "place the order,
  then cancel it". In this environment the customer's cancel always reaches the order service before
  the payment outcome has travelled through Kafka, the payment service and back, so the naive handler
  confirms the cancelled order on every run.
- **My prediction was wrong.** Before measuring, I expected the no-fault finding to be timing-dependent
  and to fail less than 10/10 (stated in [exploration.md](exploration.md) and in PR #11). It failed
  10/10. The hand-written scenario's `HOLD` makes the ordering *guaranteed*; it turns out the ordering
  also happens on its own here.

## What this does and does not show

- **"10/10 here" is not "always".** The no-fault race depends on the cancel being faster than the
  payment round trip. A slower client, a busier payment service or a different network would change
  the rate. The `HOLD` version (`scenarios/cancel-while-payment-completion-held.json`) is the one to use
  as a regression test, because it forces the ordering.
- **1-minimal, not globally minimal:** no single further reduction still fails, but a different
  sequence of reductions could in principle reach a different small schedule.
- **Only three findings were shrunk** in CI (one per category), to keep CI time reasonable. Each takes
  about a minute because every run includes a 2-second settle window.
- **A gap this exposes:** with the *fixed* order handler, "place, then cancel" leaves a cancelled order
  whose payment was captured and never refunded. No rule checks refunds yet, so StateVerdict reports
  PASS. That is a missing rule, not a pass: the next rule to add.

## Update: the no-fault race is timing-dependent after all (PR #15)

In CI run 39048156582 the no-fault finding ("place the order, cancel it") failed **9 of 10**
confirmation runs, against 10 of 10 in the earlier run. The shrinker classified it as **UNRELIABLE**
and did not shrink it, which is what it is designed to do: with a 9/10 original, a candidate that
"passes" once could just be the unlucky run. The same race reached through a fault-carrying finding
still shrank to "place, cancel" and failed 10/10 in that run. The `HOLD` schedule, which forces the
ordering, remains the regression test.

## Update: a concurrency finding keeps its concurrency (PR #16)

With `DUPLICATE_CONCURRENT` available, the last simplification of a concurrent duplicate is the
sequential duplicate. Against M03 (non-atomic idempotency), the shrinker reduced a generated finding
to one intervention, *DUPLICATE_CONCURRENT x2 on payment.requested*, and kept it concurrent because the
sequential candidate passed. The minimized schedule failed 8/10 confirmation runs: a race reproduced
reliably but not with certainty, and reported as such. In the same CI run, the no-fault cancel race
again failed 9/10 and was classified UNRELIABLE.

## The 10/10 threshold is a policy

The shrinker refuses to shrink anything that does not fail every confirmation run (by default 10/10).
That is a deliberate, conservative choice, not a law. Its cost: a real but intermittent failure, such
as the no-fault cancel race at 9/10, is reported as UNRELIABLE and left at its original size. Its
benefit: every accepted reduction is backed by a failure that never once passed, so a "removed" step
was never removed because of one unlucky pass. Alternatives exist, such as comparing failure rates
with a statistical test, or demanding k/k at a higher k for intermittent originals. They would shrink
more and cost many more runs. The threshold is named in every shrink report.
