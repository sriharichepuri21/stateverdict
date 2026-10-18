# Mutation testing: the JUnit suite and StateVerdict on the same mutants

The planted-bug comparison ([results.md](results.md)) uses nine hand-picked changes, written by the
same person who wrote the tests. Mutation testing removes the hand-picking: a tool generates many
small changes mechanically, and each approach is run against every one of them.

## Method

1. **Mutants.** [PIT](https://pitest.org) mutates the business logic of the example shop's
   production configuration (the guarded completion handler and the idempotent capture handler) with
   its default mutators: negate or change a condition, remove a call, return an empty or null value,
   and so on. Each mutant is one such change.
   - **Mutated:** checkout, applying payment outcomes, cancelling, capturing, refunds, the payment
     decision, the repositories' writes and the message listeners and publishers.
   - **Not mutated:** the deliberately broken variants and the evaluation switches that turn them on
     (the planted-bug corpus), the read-only endpoints StateVerdict uses as evidence, configuration,
     and plain records. Lines that call an evaluation switch are not mutated either.
2. **JUnit suite.** PIT runs the shop's own JUnit/Testcontainers tests against every mutant, in the
   usual way. A mutant is *detected* if a test fails or times out (PIT's convention).
3. **StateVerdict.** PIT also exports each mutant's bytecode. Each one is deployed for real: the
   service's jar with the mutated class, restarted next to the original other service. StateVerdict
   then runs its standard suite, the same one as the planted-bug comparison: the hand-written
   schedules, then 60 generated schedules (seed 42), stopping at the first FAIL. A mutant is
   *detected* if any schedule FAILs, meaning a business rule is violated in committed state.
   - A mutant whose service does not start, and INCONCLUSIVE or ERROR verdicts, are recorded
     separately and **not** counted as detected.
   - Before any mutant, every shard runs the same suite against the original services. It must PASS
     every schedule, or the shard's results are not used.
4. **The table.** Every mutant is in exactly one cell: detected by both, by the JUnit suite only, by
   StateVerdict only, or by neither. The workflow fails, and nothing is published, if any mutant is
   missing a result from either side.

**Added after the first run, before any result was published:**

- **A second, wider StateVerdict measure, "noticed":** any outcome other than PASS on every schedule
  (a FAIL, an INCONCLUSIVE or ERROR verdict, or a service that does not start). The original
  services PASS every schedule in every shard, so a non-PASS outcome is a difference the mutant
  caused; but it is not necessarily a business rule violated, so *detected* stays the headline. The
  first run showed why both are needed: many mutants (for example, one that makes checkout return
  nothing) stop a scenario from running at all, which StateVerdict reports as ERROR, not FAIL.
- **PIT's time limit was raised to 3 minutes.** In the first run PIT reported 51 of 58 mutants as
  TIMED_OUT and only 1 as KILLED. A timed-out mutant counts as detected by PIT's convention, but here
  the cause was the test set-up: after each timeout PIT starts a fresh JVM, whose first test restarts
  Spring and the test containers, which itself exceeded the 30-second limit. Those results were not
  used. With the 3-minute limit the same 52 mutants were detected, now all as KILLED, so the timeouts
  had hidden real failures rather than inflated the count. PIT's statuses (KILLED, TIMED_OUT,
  SURVIVED, NO_COVERAGE) are always reported with the totals.

Run with the `mutation` workflow. Results are published to the `evidence` branch from main only.

## Predictions, written before the first run

1. **The JUnit suite will detect more mutants overall.** Its tests check return values, published
   events and exceptions. Many mutants change something no business rule looks at: an exception
   message, input validation, or the HTTP status of a response.
2. **StateVerdict will detect some mutants the JUnit suite misses**, mainly in the atomic claim of the
   capture handler, the conditional status update and the refund path, where the effect only shows in
   committed state under duplicates, delays or a cancellation race.
3. **Mutants in correlation-id handling** (copying the run id onto events) will mostly end
   INCONCLUSIVE for StateVerdict rather than FAIL, because it ties evidence to a run through that id.
   They are not counted as detected.
4. **Some mutants will be detected by neither.** Some are equivalent (they change nothing observable)
   and some sit in code that only the planted-bug corpus reaches.

## Known limits

- One small application (two services), so the counts are small and specific to it.
- The JUnit suite and the rules were written by the same person, as in the planted-bug comparison.
- The two sides do not cost the same: PIT runs only the tests that cover a mutant, in one JVM; each
  StateVerdict run deploys the mutant and drives real messages, which takes minutes.
