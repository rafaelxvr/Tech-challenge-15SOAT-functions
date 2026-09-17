# Status notification delivery

`NotificacaoHandler` accepts exactly one FIFO SQS record. It validates the versioned B2 payload and `MessageGroupId = ordemId`, then invokes the plain `NotificarStatus` use case. Invalid payloads and unavailable dependencies fail the invocation, allowing the source mapping to retry and later send to its configured FIFO DLQ.

The DynamoDB delivery table uses a 90-second owner lease, strongly consistent decision reads, terminal-event records, and one monotonic cursor per order. Event and cursor records receive a 30-day TTL value, but expiry is checked by the code and TTL is never used for correctness. Terminal outcomes are `SES_ACCEPTED`, `SUPPRESSED`, `EXPIRED`, and `SUPERSEDED`.

SES acceptance and DynamoDB completion are separate external operations. If Lambda crashes after SES accepts an email but before `complete`, the lease can expire and a retry can send the same historical email again. `NotificarStatusTest.documentsResidualDuplicateWindowAfterSesAcceptanceBeforeCompletion` demonstrates that bounded residual duplicate window. The worker never repeats an order transition or any other business mutation.

R4 cloud acceptance must still exercise real DynamoDB conditional contention, SQS visibility/receive-count/DLQ behaviour, SES throttling, and the post-SES failure window.
