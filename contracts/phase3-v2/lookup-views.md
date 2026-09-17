# Phase 3 lookup view contract

`auth_cliente_snapshot` is the APP-owned PostgreSQL view consumed by the FUN customer-authentication adapter.

The view exposes exactly these columns:

| Column | PostgreSQL type | Meaning |
|---|---|---|
| `id` | `UUID` | Customer identifier used as the customer token subject. |
| `cpf` | `VARCHAR(11)` | Normalized CPF digits; CNPJ records are excluded. |
| `ativo` | `BOOLEAN` | Current customer active status. |
| `email` | `VARCHAR` | Current registered challenge destination. |
| `versao_identidade` | `BIGINT` | Positive identity version captured and rechecked during authentication. |

FUN queries only by a bound `cpf` or `id` parameter:

```sql
SELECT id, cpf, ativo, email, versao_identidade
FROM auth_cliente_snapshot
WHERE cpf = ?;
```

```sql
SELECT id, cpf, ativo, email, versao_identidade
FROM auth_cliente_snapshot
WHERE id = ?;
```

The function database role receives `SELECT` on this view only. It receives no write permission and no unrestricted base-table access. APP migrations version the view; compatible changes require updating the immutable contract version rather than changing `phase3-v1` in place.
