package com.oficina.functions.adapter.aws;

import com.oficina.functions.notification.ClaimResult;
import com.oficina.functions.notification.DeliveryLedger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Conditional delivery ledger. Every read used for a decision is strongly consistent. */
public final class DynamoDeliveryLedger implements DeliveryLedger {
    private static final long LEASE_MILLIS = 90_000L, RETENTION_SECONDS = 30L * 24 * 60 * 60;
    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDeliveryLedger(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        if (table == null || table.isBlank()) throw new IllegalArgumentException("Delivery table required");
        this.table = table;
    }
    @Override public ClaimResult claim(UUID eventId, UUID ordemId, UUID owner, Instant now) {
        Map<String, AttributeValue> current = dynamo.getItem(GetItemRequest.builder().tableName(table)
                .key(key(event(eventId))).consistentRead(true).build()).item();
        if (!current.isEmpty() && current.containsKey("outcome")) return ClaimResult.TERMINAL;
        long then = now.toEpochMilli();
        if (!current.isEmpty() && number(current, "leaseUntil") > then) return ClaimResult.BUSY;
        try {
            if (current.isEmpty()) {
                dynamo.putItem(PutItemRequest.builder().tableName(table).item(Map.of(
                        "PK", s(event(eventId)), "ordemId", s(ordemId.toString()), "owner", s(owner.toString()),
                        "leaseUntil", n(then + LEASE_MILLIS), "ttl", n(now.getEpochSecond() + RETENTION_SECONDS)))
                        .conditionExpression("attribute_not_exists(PK)").build());
            } else {
                dynamo.updateItem(UpdateItemRequest.builder().tableName(table).key(key(event(eventId)))
                        .conditionExpression("attribute_not_exists(outcome) AND leaseUntil <= :now")
                        .updateExpression("SET owner = :owner, leaseUntil = :lease, #ttl = :ttl")
                        .expressionAttributeNames(Map.of("#ttl", "ttl"))
                        .expressionAttributeValues(Map.of(":now", n(then), ":owner", s(owner.toString()),
                                ":lease", n(then + LEASE_MILLIS), ":ttl", n(now.getEpochSecond() + RETENTION_SECONDS))).build());
            }
            return ClaimResult.ACQUIRED;
        } catch (ConditionalCheckFailedException race) { return ClaimResult.BUSY; }
    }
    @Override public long completedSequence(UUID ordemId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder().tableName(table)
                .key(key(cursor(ordemId))).consistentRead(true).build()).item();
        return item.isEmpty() ? 0L : number(item, "sequence");
    }
    @Override public void complete(UUID eventId, UUID ordemId, long sequence, UUID owner, String outcome, String messageId, Instant now) {
        if (outcome == null || outcome.isBlank()) throw new IllegalArgumentException("Outcome required");
        // A stale DLQ replay must become terminal without trying to lower the newer order cursor.
        if ("SUPERSEDED".equals(outcome)) {
            terminalizeWithoutCursor(eventId, owner, sequence, outcome, messageId, now);
            return;
        }
        Map<String, AttributeValue> values = new java.util.HashMap<>();
        values.put(":owner", s(owner.toString())); values.put(":outcome", s(outcome)); values.put(":sequence", n(sequence));
        values.put(":ttl", n(now.getEpochSecond() + RETENTION_SECONDS));
        if (messageId != null) values.put(":messageId", s(messageId));
        String update = "SET outcome = :outcome, sequence = :sequence, completedAt = :completedAt, #ttl = :ttl" +
                (messageId == null ? " REMOVE owner, leaseUntil" : ", messageId = :messageId REMOVE owner, leaseUntil");
        values.put(":completedAt", n(now.toEpochMilli()));
        Update eventUpdate = Update.builder().tableName(table).key(key(event(eventId)))
                .conditionExpression("owner = :owner AND attribute_not_exists(outcome)").updateExpression(update)
                .expressionAttributeNames(Map.of("#ttl", "ttl")).expressionAttributeValues(values).build();
        Update cursorUpdate = Update.builder().tableName(table).key(key(cursor(ordemId)))
                .conditionExpression("attribute_not_exists(PK) OR #sequence < :sequence")
                .updateExpression("SET #sequence = :sequence, #ttl = :ttl")
                .expressionAttributeNames(Map.of("#sequence", "sequence", "#ttl", "ttl"))
                .expressionAttributeValues(Map.of(":sequence", n(sequence), ":ttl", n(now.getEpochSecond() + RETENTION_SECONDS))).build();
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(
                    TransactWriteItem.builder().update(eventUpdate).build(), TransactWriteItem.builder().update(cursorUpdate).build()).build());
        } catch (TransactionCanceledException contention) {
            // A concurrent later event can legitimately win the cursor. Preserve this terminal result but never lower it.
            if (completedSequence(ordemId) >= sequence) {
                terminalizeWithoutCursor(eventId, owner, sequence, outcome, messageId, now);
                return;
            }
            throw contention;
        }
    }
    private void terminalizeWithoutCursor(UUID eventId, UUID owner, long sequence, String outcome, String messageId, Instant now) {
        Map<String, AttributeValue> values = new java.util.HashMap<>();
        values.put(":owner", s(owner.toString())); values.put(":outcome", s(outcome)); values.put(":sequence", n(sequence));
        values.put(":completedAt", n(now.toEpochMilli())); values.put(":ttl", n(now.getEpochSecond() + RETENTION_SECONDS));
        if (messageId != null) values.put(":messageId", s(messageId));
        String update = "SET outcome = :outcome, sequence = :sequence, completedAt = :completedAt, #ttl = :ttl" +
                (messageId == null ? " REMOVE owner, leaseUntil" : ", messageId = :messageId REMOVE owner, leaseUntil");
        try {
            dynamo.updateItem(UpdateItemRequest.builder().tableName(table).key(key(event(eventId)))
                    .conditionExpression("owner = :owner AND attribute_not_exists(outcome)").updateExpression(update)
                    .expressionAttributeNames(Map.of("#ttl", "ttl")).expressionAttributeValues(values).build());
        } catch (ConditionalCheckFailedException lostClaim) {
            Map<String, AttributeValue> current = dynamo.getItem(GetItemRequest.builder().tableName(table)
                    .key(key(event(eventId))).consistentRead(true).build()).item();
            if (current.containsKey("outcome")) return;
            throw lostClaim;
        }
    }
    private static String event(UUID id) { return "delivery#" + id; }
    private static String cursor(UUID id) { return "cursor#" + id; }
    private static Map<String, AttributeValue> key(String value) { return Map.of("PK", s(value)); }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
    private static long number(Map<String, AttributeValue> values, String key) { return Long.parseLong(values.get(key).n()); }
}
