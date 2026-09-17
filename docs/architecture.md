# Functions architecture

```mermaid
flowchart LR
  API[Environment HTTP API] --> Challenge[Challenge Lambda]
  API --> Verify[Verification Lambda]
  API --> Authz[REQUEST authorizer]
  Challenge --> DDB[(DynamoDB challenge state)]
  Verify --> DDB
  Challenge --> View[(PostgreSQL auth read view)]
  Verify --> View
  Verify --> Sign[Customer RS256 signer]
  Authz --> Trust[Public RSA keys and separate staff HMAC]
  APP --> Queue[SQS FIFO and DLQ]
  Queue --> Notify[Notification Lambda]
  Notify --> Ledger[(DynamoDB delivery ledger)]
  Notify --> Recipient[(PostgreSQL recipient read view)]
  Challenge --> SES
  Notify --> SES
```

FUN owns handler/use-case/adapters and the I5 runtime Terraform source. K8S owns the base API/stage and APP integrations; FUN owns the Lambda REQUEST authorizer and the two public CPF routes using the K8S API handoff. The [reviewed state handoff](runtime-permissions.md) documents the two-phase activation. FUN does not own APP business mutations or PostgreSQL base tables. The API consumer contract is the [APP snapshot](../../Tech-challenge-15SOAT/docs/phase-3/api/contracts.md).

```mermaid
sequenceDiagram
  participant Q as FIFO source
  participant N as Notification handler
  participant D as DynamoDB ledger
  participant P as PostgreSQL recipient view
  participant S as SES
  Q->>N: One versioned event
  N->>D: Claim event lease
  alt already terminal duplicate
    N-->>Q: Return without recipient lookup
  else lease busy
    N-->>Q: Fail invocation for retry without recipient lookup
  else lease acquired
    N->>D: Read completed order sequence
    alt sequence already superseded
      N->>D: Complete SUPERSEDED without recipient lookup
    else event expired
      N->>D: Complete EXPIRED without recipient lookup
    else event eligible for recipient check
      N->>P: Read current recipient
      alt recipient missing or ineligible
        N->>D: Complete SUPPRESSED
      else eligible recipient
        N->>S: Send fixed template
        S-->>N: Acceptance
        N->>D: Complete SES_ACCEPTED and advance cursor
      end
    end
  end
```

DynamoDB provides conditional OTP consumption and event/cursor coordination without making TTL cleanup a correctness mechanism. PostgreSQL remains the current identity/recipient and business source. A crash after SES acceptance but before ledger completion can duplicate email; neither FIFO nor the ledger establishes exactly-once external delivery. See [challenge model](challenge-state.md), [trust/key rotation](token-trust.md) and [notification failure window](notification-delivery.md).

Technologies: Java 17, Maven, AWS SDK v2, Lambda, DynamoDB, SQS FIFO, SES and Terraform 1.15.8. Prerequisites: JDK 17, Docker for JDBC Testcontainers, PowerShell 7, Terraform and initialized mock-provider dependencies. There is no standalone local HTTP server or Dockerfile; handler tests use fixtures.

From this root run `./mvnw.cmd -B verify` and `pwsh -File tests/verify-infrastructure.ps1`; Linux uses `./mvnw`. [CI](../.github/workflows/ci.yml) checks PRs and pushes to main/develop with no AWS identity. [I7 prerequisites](i7-pipeline-contracts.md) keep cloud adapters disabled. See [source/evidence matrix](evidence/requirements.md), [RFC 003](../../Tech-challenge-15SOAT/docs/rfcs/003-cpf-authentication.md), [RFC 004](../../Tech-challenge-15SOAT/docs/rfcs/004-notifications.md), [ADR 003](../../Tech-challenge-15SOAT/docs/adrs/003-token-trust.md) and [ADR 004](../../Tech-challenge-15SOAT/docs/adrs/004-outbox-delivery.md).
