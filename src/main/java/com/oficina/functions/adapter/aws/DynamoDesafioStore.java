package com.oficina.functions.adapter.aws;

import com.oficina.functions.auth.Desafio;
import com.oficina.functions.auth.DesafioStore;
import com.oficina.functions.auth.OtpHasher;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** One environment-scoped table; DynamoDB TTL is only eventual garbage collection. */
public final class DynamoDesafioStore implements DesafioStore {
    private final DynamoDbClient dynamo;
    private final String table;
    private final OtpHasher hasher;
    private final Clock clock;

    public DynamoDesafioStore(DynamoDbClient dynamo, String table, OtpHasher hasher, Clock clock) {
        this.dynamo = Objects.requireNonNull(dynamo);
        if (table == null || table.isBlank()) throw new IllegalArgumentException("Challenge table required");
        this.table = table;
        this.hasher = Objects.requireNonNull(hasher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public boolean emitir(Desafio desafio, String origemHash, Instant agora) {
        long now = now(agora);
        Instant emissionTime = Instant.ofEpochMilli(now);
        if (!desafio.expiraEm().isAfter(emissionTime)) throw new IllegalStateException("Expired challenge issuance");
        long bucket = Math.floorDiv(emissionTime.getEpochSecond(), 300);
        Map<String, AttributeValue> item = new HashMap<>(key("challenge#" + desafio.id()));
        item.put("cpfHash", s(desafio.cpfHash()));
        item.put("currentChallenge", s(desafio.id().toString()));
        if (desafio.clienteId() != null) item.put("clienteId", s(desafio.clienteId().toString()));
        item.put("versaoIdentidade", n(desafio.versaoIdentidade()));
        item.put("salt", AttributeValue.builder().b(SdkBytes.fromByteArray(desafio.salt())).build());
        item.put("otpHash", AttributeValue.builder().b(SdkBytes.fromByteArray(desafio.hash())).build());
        item.put("expiresAt", n(desafio.expiraEm().toEpochMilli()));
        item.put("ttl", n(desafio.expiraEm().getEpochSecond()));
        item.put("attempts", n(0));
        item.put("consumed", bool(false));
        var create = Put.builder().tableName(table).item(item)
                .conditionExpression("attribute_not_exists(PK)").build();
        var pointer = Update.builder().tableName(table).key(key("issue#" + desafio.cpfHash()))
                .conditionExpression("attribute_not_exists(PK) OR nextAllowedAt <= :now")
                .updateExpression("SET currentChallenge = :id, nextAllowedAt = :next, #ttl = :ttl")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(Map.of(":id", s(desafio.id().toString()), ":now", n(now),
                        ":next", n(now + 60_000), ":ttl", n(Math.max(desafio.expiraEm().getEpochSecond(),
                                emissionTime.plusSeconds(60).getEpochSecond()) + 1))).build();
        var source = Update.builder().tableName(table).key(key("source#" + origemHash + "#" + bucket))
                .conditionExpression("attribute_not_exists(PK) OR emissions < :limit")
                .updateExpression("SET #ttl = :ttl ADD emissions :one")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(Map.of(":limit", n(10), ":one", n(1), ":ttl", n((bucket + 1) * 300))).build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder()
                    .clientRequestToken(desafio.id().toString()).transactItems(
                            TransactWriteItem.builder().put(create).build(),
                            TransactWriteItem.builder().update(pointer).build(),
                            TransactWriteItem.builder().update(source).build()).build());
            return true;
        } catch (TransactionCanceledException ex) {
            var reasons = ex.cancellationReasons();
            if (reasons.size() == 3 && "None".equals(reasons.get(0).code())
                    && reasons.stream().allMatch(r -> r.code() != null && Set.of("None", "ConditionalCheckFailed").contains(r.code()))
                    && reasons.stream().anyMatch(r -> "ConditionalCheckFailed".equals(r.code()))) return false;
            throw ex;
        }
    }

    @Override public Optional<Desafio> verificarEConsumir(UUID id, String codigo, Instant agora) {
        for (int read = 0; read < 3; read++) {
            var item = dynamo.getItem(GetItemRequest.builder().tableName(table)
                    .key(key("challenge#" + id)).consistentRead(true).build()).item();
            if (item.isEmpty()) return Optional.empty();
            int attempts = Math.toIntExact(number(item, "attempts"));
            if (attempts < 0 || attempts >= 5 || item.get("consumed").bool()
                    || number(item, "expiresAt") <= now(agora)) return Optional.empty();
            Desafio challenge = decode(id, item);
            if (!id.toString().equals(item.get("currentChallenge").s())) return Optional.empty();
            boolean matches = hasher.verificar(codigo, challenge.salt(), challenge.hash());
            // Hashing and contention can cross expiry. Recheck the clock for each commit.
            long commitTime = now(agora);
            if (number(item, "expiresAt") <= commitTime) return Optional.empty();
            var pointer = ConditionCheck.builder().tableName(table).key(key("issue#" + challenge.cpfHash()))
                    .conditionExpression("currentChallenge = :id")
                    .expressionAttributeValues(Map.of(":id", s(id.toString()))).build();
            var consume = Update.builder().tableName(table).key(key("challenge#" + id))
                    .conditionExpression("attempts = :observed AND attempts < :max AND expiresAt > :now AND #consumed = :false")
                    .updateExpression("SET attempts = :nextAttempts, #consumed = :consumed")
                    .expressionAttributeNames(Map.of("#consumed", "consumed"))
                    .expressionAttributeValues(Map.of(":observed", n(attempts), ":max", n(5), ":now", n(commitTime),
                            ":false", bool(false), ":nextAttempts", n(attempts + 1), ":consumed", bool(matches))).build();
            try {
                // A token belongs to this exact CAS, never to another attempt or another caller.
                dynamo.transactWriteItems(TransactWriteItemsRequest.builder().clientRequestToken(UUID.randomUUID().toString())
                        .transactItems(TransactWriteItem.builder().conditionCheck(pointer).build(),
                                TransactWriteItem.builder().update(consume).build()).build());
                return matches && challenge.expiraEm().toEpochMilli() > now(agora)
                        ? Optional.of(challenge) : Optional.empty();
            } catch (TransactionCanceledException ex) {
                if (!contention(ex)) throw ex;
            }
        }
        return Optional.empty();
    }

    @Override public void invalidar(UUID id) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder().tableName(table).key(key("challenge#" + id))
                    .conditionExpression("attribute_exists(PK)").updateExpression("SET #consumed = :true")
                    .expressionAttributeNames(Map.of("#consumed", "consumed"))
                    .expressionAttributeValues(Map.of(":true", bool(true))).build());
        } catch (ConditionalCheckFailedException ignored) {
            // Missing/TTL-deleted state is already unusable. Never remove the cooldown pointer.
        }
    }

    private long now(Instant requested) { return Math.max(requested.toEpochMilli(), clock.instant().toEpochMilli()); }
    private static boolean contention(TransactionCanceledException ex) {
        var reasons = ex.cancellationReasons();
        return reasons.size() == 2
                && reasons.stream().allMatch(r -> r.code() != null && Set.of("None", "ConditionalCheckFailed", "TransactionConflict").contains(r.code()))
                && reasons.stream().anyMatch(r -> !"None".equals(r.code()));
    }
    private static Desafio decode(UUID id, Map<String, AttributeValue> item) {
        return new Desafio(id, item.get("cpfHash").s(), item.containsKey("clienteId") ? UUID.fromString(item.get("clienteId").s()) : null,
                number(item, "versaoIdentidade"), item.get("salt").b().asByteArray(), item.get("otpHash").b().asByteArray(),
                Instant.ofEpochMilli(number(item, "expiresAt")));
    }
    private static long number(Map<String, AttributeValue> item, String field) { return Long.parseLong(item.get(field).n()); }
    private static Map<String, AttributeValue> key(String value) { return Map.of("PK", s(value)); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static AttributeValue bool(boolean value) { return AttributeValue.builder().bool(value).build(); }
}
