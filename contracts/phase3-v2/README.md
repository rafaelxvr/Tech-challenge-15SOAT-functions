# Phase 3 v2 cross-repository contract

APP owns this immutable contract version. `status-event.json` defines the notification event, `token-claims.json` fixes the two environment trust domains and purposes, `routes.json` is the explicit default-deny gateway matrix, and `lookup-views.md` defines the read-only customer lookup boundary.

FUN vendors these files without a runtime dependency on APP and records their SHA-256 values in `SHA256SUMS`. A contract change creates a new versioned directory; published contract versions are not edited in place.

Version 2 adds only the ADMIN-only `GET /api/admin/relatorios/ordens` route owned by `RelatoriosAdminController.consultar`. Every v1 route and policy is preserved. The event, token claims and lookup views are byte-identical to v1; their schema versions do not change. The complete v1 snapshot remains frozen alongside this version.

APP uses v2 as its current controller/gateway matrix. F3, I4 and I5 must vendor or consume v2 before exposing the new report through the gateway. A gateway still using v1 correctly denies this unlisted route by default. A7 does not modify external repositories or FUN's existing v1 vendor.

The route matrix uses one grant per allowed actor. A staff grant lists its accepted roles. A customer grant lists its required scopes and still requires APP to verify current identity status, identity version, ownership, and domain rules. An empty grant list is an explicit deny.

The status event contains identifiers and transition metadata only. It must remain at most 8192 bytes and must not contain CPF, email, JWT, authorization, contact, or telephone data.
