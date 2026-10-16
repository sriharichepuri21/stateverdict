# ADR-001: StateVerdict core is a modular monolith; the example services are the system under test

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

StateVerdict tests business correctness across independently running services. Two very different
kinds of software live in this repository:

1. **The tool itself**: scenario runner, dispatch scheduler, evidence collection, rule evaluation,
   fault exploration, shrinking and reporting.
2. **A system under test (SUT)**: a small commerce application (order, payment and later inventory
   services) used to demonstrate and evaluate the tool.

It would be easy to build the tool itself as several microservices to "look distributed". That adds
network calls, deployment work and failure modes to the tool without making it better at its job.

## Decision

- The StateVerdict core is built as **one Java application with clear internal modules**
  (scenario, runner, scheduler, evidence, invariants, explorer, shrinker, reporting), driven by a CLI.
- The **distributed behaviour lives in the system under test**: separate Spring Boot services, each
  with its own database and credentials, communicating through Kafka.
- Everything lives in one repository (a monorepo) while we control both sides, so local setup and
  CI stay simple. Module boundaries are kept clean so the core can later target applications outside
  this repository.

## Consequences

- One process to run, debug and test for the tool; integration complexity is concentrated where it is
  the subject of testing.
- The example services must not depend on StateVerdict core code, except through the test-mode
  dispatch adapter described in the specification (section 3), which production mode bypasses.
- If the core later needs to run as a hosted service, its modules can be split at the existing
  boundaries. That is explicitly out of scope for v1.

## Alternatives considered

- **Microservices for the tool itself:** rejected; extra operational cost with no testing benefit.
- **Separate repositories for tool and example:** rejected for now; it slows down changes that touch
  both sides, which is most early work.
