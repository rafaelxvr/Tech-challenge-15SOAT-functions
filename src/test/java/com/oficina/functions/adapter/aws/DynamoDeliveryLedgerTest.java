package com.oficina.functions.adapter.aws;

import com.oficina.functions.notification.ClaimResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.Instant; import java.util.*;
import static org.assertj.core.api.Assertions.*; import static org.mockito.Mockito.*;

class DynamoDeliveryLedgerTest {
    private final UUID event = UUID.randomUUID(), order = UUID.randomUUID(), owner = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-15T12:00:00Z");
    @Test void claimsAbsentEventConditionallyAndRetainsThirtyDays() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class); when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        DynamoDeliveryLedger ledger = new DynamoDeliveryLedger(dynamo, "delivery");
        assertThat(ledger.claim(event, order, owner, now)).isEqualTo(ClaimResult.ACQUIRED);
        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class); verify(dynamo).putItem(request.capture());
        assertThat(request.getValue().conditionExpression()).contains("attribute_not_exists"); assertThat(request.getValue().item().get("ttl").n()).isEqualTo(Long.toString(now.plusSeconds(30L * 24 * 60 * 60).getEpochSecond()));
    }
    @Test void terminalAndActiveLeaseAreNotReclaimed() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class); Map<String, AttributeValue> terminal = Map.of("outcome", s("SES_ACCEPTED")); when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(terminal).build());
        assertThat(new DynamoDeliveryLedger(dynamo, "delivery").claim(event, order, owner, now)).isEqualTo(ClaimResult.TERMINAL);
        Map<String, AttributeValue> leased = Map.of("leaseUntil", n(now.plusSeconds(1).toEpochMilli())); when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(leased).build());
        assertThat(new DynamoDeliveryLedger(dynamo, "delivery").claim(event, order, owner, now)).isEqualTo(ClaimResult.BUSY);
    }
    @Test void completionUsesOwnerConditionAndMonotonicCursorTransaction() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class); DynamoDeliveryLedger ledger = new DynamoDeliveryLedger(dynamo, "delivery"); ledger.complete(event, order, 7, owner, "SES_ACCEPTED", "ses-1", now);
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class); verify(dynamo).transactWriteItems(request.capture());
        var updates = request.getValue().transactItems(); assertThat(updates).hasSize(2); assertThat(updates.get(0).update().conditionExpression()).contains("owner", "outcome"); assertThat(updates.get(1).update().conditionExpression()).contains("sequence < :sequence");
    }
    @Test void readsCursorConsistently() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class); when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of("sequence", n(3))).build());
        assertThat(new DynamoDeliveryLedger(dynamo, "delivery").completedSequence(order)).isEqualTo(3); ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class); verify(dynamo).getItem(request.capture()); assertThat(request.getValue().consistentRead()).isTrue();
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); } private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
