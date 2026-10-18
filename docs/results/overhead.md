# What the adapter costs with no fault injected

The adapter sits in every message listener of a service under test, so the first question is
whether it slows the service down. Source: [benchmark 39168087308](https://github.com/sriharichepuri21/stateverdict/actions/runs/39168087308)
(commit `81563c4`, run on main, record `runs/benchmark/39168087308.json` on the `evidence` branch,
complete and citable). Re-run with the `benchmark` workflow.

## Per message: the dispatcher alone (JMH)

One message through the dispatcher on a single thread, like a Kafka listener on one partition. The
handler does nothing, so this isolates the dispatcher. 3 forks × 10 one-second iterations.

| Variant | ns per message | ± (99.9% CI) | Added over the plain call |
|---|---|---|---|
| Handler called directly (no adapter) | 2.33 | 0.02 | — |
| Adapter, production mode (`DirectDispatcher`) | 37.58 | 0.20 | **≈ 35 ns** |
| Adapter, test mode, no fault armed (`ControlledDispatcher`) | 1,056.85 | 55.45 | ≈ 1.05 µs |

Production mode builds the message envelope (topic, run id, event key) and makes one extra call. Test
mode also looks up armed faults and writes a journal entry, under a lock.

## End to end: the external app with and without the adapter

The [external app](../../targets/piomin-saga/README.md) was built three ways and each started fresh: the
**unpatched upstream** code, the app **with the adapter in production mode**, and **in test mode with no
fault armed**. 3 rounds in rotated order, 300 orders per run at 10 per second after 50 warm-up orders.
Latency is the time from the order event to the later of the two reservation replies (payment and
stock), the path the adapter sits on, from Kafka record timestamps (millisecond resolution).

| Configuration | Orders | Median | p95 | Mean | Mean vs upstream (95% CI) |
|---|---|---|---|---|---|
| Upstream, no adapter | 900 | 11 ms | 18 ms | 12.42 ms | — |
| Adapter, production mode | 900 | 11 ms | 19 ms | 12.45 ms | +0.03 ms [−0.33, +0.38] |
| Adapter, test mode, no fault | 900 | 11 ms | 19 ms | 12.50 ms | +0.08 ms [−0.27, +0.46] |

Round medians were 11–12 ms in every configuration. Both confidence intervals include zero, so **the
adapter's cost is not measurable end to end**; an overhead above about half a millisecond on the mean
would have shown up. That matches the microbenchmark: nanoseconds to about a microsecond per message,
against handlers that read and write a database and send to Kafka in milliseconds.

## What this does and does not show

- **One app, one machine type, a light load** (10 orders per second). It measures latency, not
  throughput under saturation. Test mode takes a lock per message, which could matter at high
  concurrency; that was not measured.
- **The end-to-end resolution is 1 ms** (Kafka timestamps), so the medians cannot show sub-millisecond
  differences; the means and their intervals are what bound the overhead.
- **Test mode keeps a journal entry per delivery until a run is reset**, so memory grows in a long
  test session that never resets. StateVerdict resets the runs it starts, but in the external app every
  order is journaled under its own run id, so orders placed outside StateVerdict accumulate until the
  service restarts. Production mode keeps nothing.
- The script checks that the jars are what each configuration claims (the adapter is absent from
  upstream and present in the patched builds), and that test-mode deliveries went through the
  controlled dispatcher (a journal entry for a measured order).
