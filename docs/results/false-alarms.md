# False alarms on valid scenarios

A tool that cries wolf gets ignored, so this measures how often StateVerdict reports a violation when
there is none. Source: [exploration 39153869192](https://github.com/sriharichepuri21/stateverdict/actions/runs/39153869192)
(commit `31e2c3c`, run on main, record `runs/exploration/39153869192.json` on the `evidence` branch,
complete and citable).

| App | Valid scenarios | PASS | FAIL (false alarm) | INCONCLUSIVE | ERROR |
|---|---|---|---|---|---|
| Example shop, corrected handlers | 360 (6 seeds × 60) | 360 | **0** | 0 | 0 |
| External app (piomin saga) | 150 (5 seeds × 30) | 150 | **0** | 0 | 0 |
| **Total** | **510** | **510** | **0** | **0** | **0** |

## What counts as a valid scenario

- **Example shop:** generated fault schedules (duplicates, concurrent duplicates, delays, holds, customer
  cancellations at different moments, approved and declined payments) against the **corrected**
  handlers. The application is designed to handle all of these, so every run should PASS.
- **External app:** `explore-saga --controls true` generates only scenarios the application is designed
  to handle: delays of 100 to 2000 ms on any of its four messages (inside the 10 s join window), and
  orders that are either accepted or rejected by both services.

## What this does and does not show

- **The external controls exclude the app's three known defect triggers on purpose:** a rejection by
  only one service, duplicates, and delays beyond the join window. Those are measured separately as
  findings. Including them would turn real defects into "false alarms".
- **0 out of 510 is not a false-positive rate of zero in general.** It is the result on these two
  applications and these generators. A different application or fault type could produce false
  alarms, and the controls run nightly so a regression would show up.
- FAIL, INCONCLUSIVE and ERROR are reported separately, as they mean different things. A
  missing-evidence run is INCONCLUSIVE, never PASS, and none occurred here.
