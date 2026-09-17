# Authentication challenge state

## DynamoDB contract

Each deployment environment has a separate challenge table, configured by the Lambda bootstrap. Its only key is string `PK`. The same table contains:

| PK | Attributes | Lifetime |
| --- | --- | --- |
| `challenge#{uuid}` | `cpfHash`, `currentChallenge` (UUID), optional `clienteId`, `versaoIdentidade`, binary `salt` / `otpHash`, numeric `expiresAt`, `attempts`, boolean `consumed`, `ttl` | Five-minute challenge; dummy records use identical state without `clienteId` |
| `issue#{cpfHash}` | `currentChallenge`, `nextAllowedAt`, `ttl` | Pointer retained through both challenge expiry and cooldown |
| `source#{origemHash}#{bucket}` | `emissions`, `ttl` | Epoch bucket `floor(epochSeconds / 300)`; environment isolation comes from the table |

`expiresAt` and `nextAllowedAt` are epoch **milliseconds**. `ttl` is epoch **seconds**, used only for eventual cleanup; retaining an expired record never makes it valid. Pointer TTL is later than both challenge expiry and the complete cooldown. Source TTL is the bucket end. Raw CPF, source, email and OTP are never stored. The hashes of CPF/source are pseudonymous identifiers and still require restricted table access.

Issuance performs one `TransactWriteItems` containing a conditional new-challenge `Put`, a pointer `Update` allowed only after 60 seconds, and an atomic bucket counter increment allowed only while below 10. A cancelled transaction changes none of those items. Thus rejected emissions do not replace the pointer or increment the accepted-emission count. Unknown/inactive valid CPFs create dummy challenges with exactly the same limits and cleanup. A single UUID-derived client request token makes retries of the exact issuance SDK request idempotent. Sending email is outside this transaction.

Consumption reads the challenge consistently, checks its immutable issuance reference, expiry, consumed flag and five-attempt budget, then computes PBKDF2 locally. One transaction checks the per-CPF pointer still references this UUID and updates the challenge only if the observed attempts still match, attempts remain below five, expiry is later than the current request timestamp, and consumed is false. `consumed` and `ttl` are expression aliases because they are DynamoDB reserved words. Incorrect/malformed codes commit one attempt with consumed false; correct codes commit consumed true. Each exact CAS request has its own idempotency token. Only a successful transaction with a correct code returns a snapshot; a response arriving at/after expiry returns no snapshot even if the consume committed.

Conditional/transaction contention triggers at most **three consistent reads total**, each with a new observed count and refreshed time. Other dependency failures propagate to F1's redacted service-unavailable response. Exhausted contention fails authentication safely. The adapter uses the later of the supplied timestamp and its injected clock; F4 must inject the same system clock used by the use cases. The bootstrap must configure bounded SDK call/attempt timeouts. `invalidar` conditionally marks the challenge consumed, tolerates a missing record, and leaves the cooldown/pointer intact.

The requested atomicity follows [DynamoDB transaction semantics](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html). Mockito tests inspect SDK request shape and local branching; they do **not** establish live DynamoDB condition evaluation, serializability or race behavior.

## Restricted PostgreSQL lookup

`JdbcClienteLookup` implements F1 `ClienteLookup` with exactly the parameterized CPF/UUID SELECT statements in `contracts/phase3-v1/lookup-views.md`. It reads only `auth_cliente_snapshot`, never a base table. The APP-owned ordinary view exposes CPF customers, excludes CNPJ rows, and reflects contact/identity changes on the next statement. APP migrations own the production view and grants; this repository creates its fixture only in isolated Testcontainers PostgreSQL.

The role must have schema `USAGE` and `SELECT` on that view only, without base-table SELECT/UPDATE or view UPDATE. Application checks do not substitute for those grants. Keep schema CREATE revoked from untrusted roles so the fixed unqualified view name cannot be shadowed through the connection's configured search path.

F4 bootstrap must instantiate **one shared `ConnectionProvider` per Lambda execution environment** and inject it into every JDBC adapter. It caches at most one connection, serializes JDBC work on the provider, checks liveness on reuse, closes stale connections before replacement, and discards connections on statement failure. Statements/results are closed on every lookup; adapters must not close the reusable connection. Auto-commit gives each lookup a fresh statement snapshot. No pool is created.

Configuration is separate host/port/database/user/password plus an absolute CA certificate path; URL options cannot be injected through host/database. The provider forces `sslmode=verify-full`, CA trust and hostname validation, and read-only mode. Time bounds: connect 3 s, login 5 s, socket 5 s, cancellation signal 2 s, liveness 2 s, statement 3 s. Config `toString` and propagated JDBC errors are redacted; credentials must come from F4's secret bootstrap. See [pgJDBC connection parameters](https://jdbc.postgresql.org/documentation/use/).

## Local verification and R4 acceptance

Run Java 17 and Docker Desktop, then `./mvnw.cmd verify` (Windows) or `./mvnw verify` (Unix). `JdbcClienteLookupTest` uses an ephemeral PostgreSQL 18 container and generated test-only TLS certificate. It proves restricted grants, bound parameters, CNPJ exclusion, live view changes, valid TLS, rejection of an untrusted certificate/incorrect hostname and recovery after PostgreSQL terminates the cached backend. `ConnectionProviderTest` also checks timeout configuration and socket lifecycle. The test-only Docker API floor is 1.44 for Docker Engine 29 compatibility.

R4 cloud acceptance remains required for simultaneous issuance/consumption, source/cooldown boundary races, transaction cancellation reasons, TTL lag, SDK timeout/retry ambiguity and runtime clock/network latency. No AWS/DynamoDB calls were made for F2. F1 in-memory concurrency tests cover use-case behavior independently.

The F1 delivery/invalidation recovery concern remains open: SES may accept delivery and then report a failure, while the subsequent `invalidar` call can also fail. The consume/invalidation transaction cannot prove recovery from that combined failure, and retrying the whole use case can generate a new challenge or deliver again. F2 does **not** claim exactly-once delivery or a durable recovery design. R4 must exercise and resolve this with an adapter-tested delivery/recovery protocol before production acceptance.
