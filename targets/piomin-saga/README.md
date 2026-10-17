# External target: piomin/sample-spring-kafka-microservices

StateVerdict's first application written by someone else:
[piomin/sample-spring-kafka-microservices](https://github.com/piomin/sample-spring-kafka-microservices),
a widely used Spring Boot and Kafka example of a saga. An order service asks a payment service and a
stock service to reserve, joins their two replies (Kafka Streams, 10 s window), and broadcasts the
decision, which both services then settle.

## What is in this directory

| File | Purpose | Licence |
|---|---|---|
| `target.env` | The repository and the exact commit tested (`a76daeb`) | |
| `adapter.patch` | The changes StateVerdict needs in the target (below) | **GPL-3.0**, as a modification of a GPL-3.0 program |
| `LICENSE-GPL-3.0.md` | The target's licence, which covers `adapter.patch` | |
| `prepare.sh` | Fetches the pinned commit, applies the patch, builds the services | Apache-2.0 |
| `docker-compose.yml`, `wait-ready.sh`, `measure.sh` | Run the patched target and measure schedules | Apache-2.0 |
| `scenarios/*.json` | Hand-written schedules | Apache-2.0 |
| `PREDICTIONS.md` | What was expected, committed before the first run | |

The target's code is never copied into this repository. CI downloads it at the pinned commit.

## The adapter (143 added lines, 3 changed)

- **Payment and stock listeners** pass each message through StateVerdict's dispatcher. The message is
  labelled `orders.reserve` (a new order) or `orders.settle` (the decision), with run id
  `order-<id>`. In the default `direct` mode this is one delivery, as before. Each delivery gets its
  own copy of the message, because a real Kafka redelivery is deserialized afresh and the target's
  handlers modify the order they receive.
- **Test-only endpoints** (`/_stateverdict/customers`, `/_stateverdict/products`) create a fresh
  customer or product for a run and read its committed state back. They exist only in controlled
  mode.
- **The order service** keeps an order id that the client supplies, so a run can arm its faults
  before the order exists. Without one, it assigns ids as before.
- **No business logic is changed.**

## Running it

```bash
targets/piomin-saga/prepare.sh
docker compose -f targets/piomin-saga/docker-compose.yml up -d
targets/piomin-saga/wait-ready.sh
java -jar apps/stateverdict-core/target/stateverdict-core-*.jar run-saga targets/piomin-saga/scenarios/baseline.json
```
