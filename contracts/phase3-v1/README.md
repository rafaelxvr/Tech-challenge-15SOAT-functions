# Phase 3 v1 cross-repository contract

APP owns this immutable contract version. `status-event.json` defines the notification event, `token-claims.json` fixes the two environment trust domains and purposes, `routes.json` is the explicit default-deny gateway matrix, and `lookup-views.md` defines the read-only customer lookup boundary.

FUN vendors these files without a runtime dependency on APP and records their SHA-256 values in `SHA256SUMS`. A contract change creates a new versioned directory; published `phase3-v1` files are not edited in place.

The route matrix uses one grant per allowed actor. A staff grant lists its accepted roles. A customer grant lists its required scopes and still requires APP to verify current identity status, identity version, ownership, and domain rules. An empty grant list is an explicit deny.

The status event contains identifiers and transition metadata only. It must remain at most 8192 bytes and must not contain CPF, email, JWT, authorization, contact, or telephone data.
