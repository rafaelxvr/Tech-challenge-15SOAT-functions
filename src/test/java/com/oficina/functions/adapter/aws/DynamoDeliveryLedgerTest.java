package com.oficina.functions.adapter.aws;

import com.oficina.functions.notification.ClaimResult;
import com.oficina.functions.notification.Destinatario;
import com.oficina.functions.notification.NotificarStatus;
import com.oficina.functions.notification.StatusOrdemServicoRegistrado;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.Clock; import java.time.Instant; import java.time.ZoneOffset; import java.util.*;
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
    @Test void terminalizesOldDlqReplayWithoutReplacingNewerCursor() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class); DynamoDeliveryLedger ledger = new DynamoDeliveryLedger(dynamo, "delivery");
        ledger.complete(event, order, 2, owner, "SUPERSEDED", null, now);
        ArgumentCaptor<UpdateItemRequest> eventTerminalization = ArgumentCaptor.forClass(UpdateItemRequest.class); verify(dynamo).updateItem(eventTerminalization.capture());
        assertThat(eventTerminalization.getValue().key().get("PK").s()).startsWith("delivery#");
        assertThat(eventTerminalization.getValue().conditionExpression()).contains("owner", "outcome");
        verify(dynamo, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }
    @Test void resolvesActualCursorConditionRaceByTerminalizingWithoutCursor() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of("sequence", n(9))).build());
        doThrow(TransactionCanceledException.builder().message("cursor condition failed").build()).when(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
        new DynamoDeliveryLedger(dynamo, "delivery").complete(event, order, 7, owner, "SES_ACCEPTED", "ses-1", now);
        verify(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class)); verify(dynamo).updateItem(any(UpdateItemRequest.class));
    }
    @Test void oldDlqReplayAcknowledgesThroughUseCaseWithoutSesOrCursorWrite() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        when(dynamo.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            String pk = call.getArgument(0, GetItemRequest.class).key().get("PK").s();
            return GetItemResponse.builder().item(pk.startsWith("cursor#") ? Map.of("sequence", n(9)) : Map.of("leaseUntil", n(0))).build();
        });
        DynamoDeliveryLedger ledger = new DynamoDeliveryLedger(dynamo, "delivery");
        Destinatario recipient = new Destinatario(order, 1001, UUID.randomUUID(), true, "customer@example.test", 1);
        StatusOrdemServicoRegistrado stale = new StatusOrdemServicoRegistrado(event, "StatusOrdemServicoRegistrado", 1, order, 1001,
                recipient.clienteId(), 1, 2, null, "RECEBIDA", now, UUID.randomUUID(), null);
        var sender = mock(com.oficina.functions.notification.StatusEmailSender.class);
        new NotificarStatus(id -> Optional.of(recipient), ledger, sender, Clock.fixed(now, ZoneOffset.UTC)).executar(stale, order.toString());
        verifyNoInteractions(sender); verify(dynamo, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
        ArgumentCaptor<UpdateItemRequest> updates = ArgumentCaptor.forClass(UpdateItemRequest.class); verify(dynamo, times(2)).updateItem(updates.capture());
        assertThat(updates.getAllValues()).allSatisfy(update -> assertThat(update.key().get("PK").s()).isEqualTo("delivery#" + event));
        UpdateItemRequest claim = updates.getAllValues().get(0);
        UpdateItemRequest terminal = updates.getAllValues().get(1);
        assertThat(terminal.conditionExpression()).contains("owner", "outcome");
        assertThat(terminal.expressionAttributeValues()).containsKeys(":owner", ":outcome");
        assertThat(terminal.expressionAttributeValues().get(":owner")).isEqualTo(claim.expressionAttributeValues().get(":owner"));
        assertThat(terminal.expressionAttributeValues().get(":outcome").s()).isEqualTo("SUPERSEDED");
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); } private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
