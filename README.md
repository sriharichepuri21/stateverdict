# StateVerdict

**Fault exploration and evidence-based correctness testing for event-driven microservices.**

Every service in a checkout flow can return `200 OK` and the business outcome can still be wrong:
an order confirmed after the payment was declined, or one purchase charged twice because a message
was delivered twice. StateVerdict runs business workflows under controlled faults (duplicated,
delayed and reordered events), checks the committed state of each service against business rules,
and reports one of four verdicts: **PASS**, **FAIL**, **INCONCLUSIVE** or **ERROR**.

> **Status:** early development. The design is complete
> ([engineering specification](docs/specification/StateVerdict_Engineering_Specification_v1.0.pdf));
> implementation is in progress. Nothing below is a measured result yet.

## What's in this repository

| Path | What it is |
|---|---|
| `examples/commerce/` | The system under test: small order and payment services that talk through Kafka |
| `infrastructure/compose/` | Docker Compose for PostgreSQL, Kafka and the services |
| `docs/decisions/` | Architecture Decision Records |
| `docs/specification/` | The full engineering specification |

## Running it

You need Docker. The easiest way is a GitHub Codespace (Code → Codespaces → Create), which comes
with Java 21, Maven and Docker already set up.

```bash
# Build and start PostgreSQL, Kafka, and both services
docker compose -f infrastructure/compose/docker-compose.yml up --build -d

# Check both services are healthy
curl -s localhost:8081/actuator/health   # order-service
curl -s localhost:8082/actuator/health   # payment-service

# Place one order and check exactly one payment capture is committed
scripts/checkout-smoke.sh

# Run all tests (uses Testcontainers, so Docker must be running)
mvn -B verify
```

## Guiding principle

Never report a verified business outcome without sufficient, trustworthy evidence.
Missing evidence produces **INCONCLUSIVE**, never **PASS**.
