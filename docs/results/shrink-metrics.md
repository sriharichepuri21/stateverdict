# Shrinking, measured

How much the shrinker removes, what it costs, and how reliable the result is. All figures are from
one citable run, [exploration 39104915684](https://github.com/sriharichepuri21/stateverdict/actions/runs/39104915684)
(commit `5bf862b`, recorded on the `evidence` branch). Each nightly run records the same fields.

An *intervention* is one injected fault, a customer cancel (example shop), or one service rejecting
the order (external app). A candidate is kept only if it fails 3/3; the original and the result are
each confirmed over 10 runs.

| App | Finding | Before | After | Reduction | Candidates | Runs | Time | Confirmed |
|---|---|---|---|---|---|---|---|---|
| Example shop | duplicate charge (SV-002) | 3 | 1 | 67% | 5 | 29 | 2.3 min | 10/10 |
| Example shop | cancelled order confirmed (SV-003) | 3 | 1 | 67% | 3 | 27 | 3.2 min | 10/10 |
| External app | order never decided (SAGA-1) | 5 | 1 | 80% | 10 | 38 | 17.0 min | 10/10 |
| External app | money wrong after a rejection (SAGA-2) | 4 | 1 | 75% | 4 | 30 | 6.6 min | 10/10 |
| External app | stock wrong after a rejection (SAGA-3) | 3 | 1 | 67% | 3 | 27 | 2.8 min | 10/10 |
| External app | money wrong, normal order (SAGA-2) | 4 | 1 | 75% | 6 | 32 | 4.1 min | 10/10 |

**Summary: 6 multi-fault findings went from 22 interventions to 6 (73% fewer), between 67% and 80%
each, and every minimal schedule failed 10/10.** Cost: 27 to 38 runs and 2 to 17 minutes per finding.
The 17-minute case is the order that is never decided, where every run waits out the 25 s decision
deadline.

A seventh finding, the example shop's no-fault cancel race, was already a single intervention, so
there was nothing to remove. It is excluded from the reduction figures. In this run it reproduced
10/10; in two earlier runs it reproduced 9/10 and was left unshrunk (see [shrinking.md](shrinking.md)).

## What these numbers do and do not mean

- **Fewer interventions is not the same as less debugging time.** It means the failing scenario
  handed to an engineer has one cause instead of three to five. How much time that saves was not
  measured.
- **Locally minimal, not globally minimal.** No single further removal still fails. A different
  combination could in principle be smaller.
- **One run.** The same findings shrank to the same causes in the two earlier runs that shrank them
  (CI 39072498345 and exploration #1). The candidate and run counts can differ slightly between runs.
