package com.oficina.functions.adapter.aws;

import com.oficina.functions.auth.Desafio;
import com.oficina.functions.auth.OtpHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamoDesafioStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final OtpHasher hasher = new OtpHasher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DynamoDesafioStore store = new DynamoDesafioStore(dynamo, "auth-test", hasher, clock);

    @Test void consumeChecksCurrentPointerAndObservedStateInOneTransaction() {
        Desafio challenge = challenge(false);
        read(challenge, 2, false);
        assertThat(store.verificarEConsumir(challenge.id(), "123456", NOW).orElseThrow()).usingRecursiveComparison().isEqualTo(challenge);
        var request = transaction();
        assertThat(request.transactItems()).hasSize(2);
        var pointer = request.transactItems().get(0).conditionCheck();
        assertThat(pointer.key().get("PK").s()).isEqualTo("issue#cpf-hash");
        assertThat(pointer.conditionExpression()).isEqualTo("currentChallenge = :id");
        assertThat(pointer.expressionAttributeValues().get(":id").s()).isEqualTo(challenge.id().toString());
        var consume = request.transactItems().get(1).update();
        assertThat(consume.conditionExpression()).contains("attempts = :observed", "attempts < :max", "expiresAt > :now", "#consumed = :false");
        assertThat(consume.expressionAttributeNames()).containsEntry("#consumed", "consumed");
        assertThat(consume.expressionAttributeValues().get(":observed").n()).isEqualTo("2");
        assertThat(consume.expressionAttributeValues().get(":consumed").bool()).isTrue();
        assertThat(request.clientRequestToken()).isNotBlank();
        verify(dynamo).getItem(argThat((GetItemRequest r) -> r.consistentRead()));
        verify(dynamo, never()).putItem(any(PutItemRequest.class));
    }

    @Test void issuanceAtomicallyCreatesChallengePointerAndCappedBucketIncludingDummy() {
        for (boolean dummy : List.of(false, true)) {
            reset(dynamo);
            Desafio challenge = challenge(dummy);
            assertThat(store.emitir(challenge, "source-hash", NOW)).isTrue();
            var items = transaction().transactItems();
            assertThat(items).hasSize(3);
            var put = items.get(0).put();
            assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(PK)");
            assertThat(put.item().get("PK").s()).isEqualTo("challenge#" + challenge.id());
            assertThat(put.item().get("ttl").n()).isEqualTo(Long.toString(NOW.plusSeconds(300).getEpochSecond()));
            assertThat(put.item().containsKey("clienteId")).isEqualTo(!dummy);
            assertThat(put.item()).doesNotContainKeys("cpf", "email", "codigo");
            var pointer = items.get(1).update();
            assertThat(pointer.key().get("PK").s()).isEqualTo("issue#cpf-hash");
            assertThat(pointer.conditionExpression()).contains("nextAllowedAt <= :now");
            assertThat(pointer.expressionAttributeValues().get(":next").n()).isEqualTo(Long.toString(NOW.plusSeconds(60).toEpochMilli()));
            var source = items.get(2).update();
            assertThat(source.key().get("PK").s()).isEqualTo("source#source-hash#" + NOW.getEpochSecond() / 300);
            assertThat(source.conditionExpression()).contains("emissions < :limit");
            assertThat(source.expressionAttributeValues().get(":limit").n()).isEqualTo("10");
            assertThat(source.updateExpression()).contains("ADD emissions :one");
        }
    }

    @Test void onlyCooldownOrSourceConditionalRejectionMeansThrottled() {
        when(dynamo.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(cancel("None", "ConditionalCheckFailed", "None"))
                .thenThrow(cancel("None", "None", "ConditionalCheckFailed"))
                .thenThrow(cancel("ConditionalCheckFailed", "None", "None"))
                .thenThrow(cancel("None", "ProvisionedThroughputExceeded", "None"));
        assertThat(store.emitir(challenge(false), "source", NOW)).isFalse();
        assertThat(store.emitir(challenge(true), "source", NOW)).isFalse();
        assertThatThrownBy(() -> store.emitir(challenge(false), "source", NOW)).isInstanceOf(TransactionCanceledException.class);
        assertThatThrownBy(() -> store.emitir(challenge(false), "source", NOW)).isInstanceOf(TransactionCanceledException.class);
    }

    @Test void wrongAndMalformedCodesCommitOneAttemptWithoutConsumption() {
        for (String code : new String[]{"999999", "invalid", null}) {
            reset(dynamo);
            Desafio challenge = challenge(false);
            read(challenge, 4, false);
            assertThat(store.verificarEConsumir(challenge.id(), code, NOW)).isEmpty();
            var update = transaction().transactItems().get(1).update();
            assertThat(update.updateExpression()).contains("attempts = :nextAttempts");
            assertThat(update.expressionAttributeValues().get(":nextAttempts").n()).isEqualTo("5");
            assertThat(update.expressionAttributeValues().get(":consumed").bool()).isFalse();
        }
    }

    @Test void expiryAttemptsConsumedAndMissingAreRejectedWithoutWritingEvenBeforeTtlCleanup() {
        Desafio valid = challenge(false);
        Desafio expired = new Desafio(valid.id(), valid.cpfHash(), valid.clienteId(), 1,
                valid.salt(), valid.hash(), NOW);
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(
                GetItemResponse.builder().item(item(expired, 0, false)).build(),
                GetItemResponse.builder().item(item(valid, 5, false)).build(),
                GetItemResponse.builder().item(item(valid, 0, true)).build(),
                GetItemResponse.builder().build());
        for (int i = 0; i < 4; i++) assertThat(store.verificarEConsumir(valid.id(), "123456", NOW)).isEmpty();
        verify(dynamo, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test void contentionHasAtMostThreeConsistentReadsAndNeverReturnsUncommittedChallenge() {
        Desafio challenge = challenge(false);
        read(challenge, 0, false);
        when(dynamo.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(cancel("ConditionalCheckFailed", "None"));
        assertThat(store.verificarEConsumir(challenge.id(), "123456", NOW)).isEmpty();
        verify(dynamo, times(3)).getItem(any(GetItemRequest.class));
        verify(dynamo, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test void contentionRefreshesObservedAttemptCountAndCanCommit() {
        Desafio challenge = challenge(false);
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(
                GetItemResponse.builder().item(item(challenge, 0, false)).build(),
                GetItemResponse.builder().item(item(challenge, 1, false)).build());
        when(dynamo.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(cancel("None", "TransactionConflict"))
                .thenReturn(TransactWriteItemsResponse.builder().build());
        assertThat(store.verificarEConsumir(challenge.id(), "123456", NOW).orElseThrow()).usingRecursiveComparison().isEqualTo(challenge);
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo, times(2)).transactWriteItems(capture.capture());
        assertThat(capture.getValue().transactItems().get(1).update().expressionAttributeValues().get(":observed").n()).isEqualTo("1");
    }

    @Test void dependencyFailuresAreNotConvertedToInvalidCodesOrRetried() {
        Desafio challenge = challenge(false);
        read(challenge, 0, false);
        when(dynamo.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(cancel("None", "ProvisionedThroughputExceeded"));
        assertThatThrownBy(() -> store.verificarEConsumir(challenge.id(), "123456", NOW))
                .isInstanceOf(TransactionCanceledException.class);
        verify(dynamo).getItem(any(GetItemRequest.class));
    }

    @Test void invalidationIsConditionalAndDoesNotClearPointerOrCooldown() {
        UUID id = UUID.randomUUID();
        store.invalidar(id);
        var capture = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(capture.capture());
        assertThat(capture.getValue().key().get("PK").s()).isEqualTo("challenge#" + id);
        assertThat(capture.getValue().conditionExpression()).isEqualTo("attribute_exists(PK)");
        assertThat(capture.getValue().updateExpression()).isEqualTo("SET #consumed = :true");
        assertThat(capture.getValue().expressionAttributeNames()).containsEntry("#consumed", "consumed");
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(ConditionalCheckFailedException.builder().build());
        assertThatCode(() -> store.invalidar(id)).doesNotThrowAnyException();
    }

    @Test void expirationDuringHashingOrCommitCannotReturnAnAuthenticationSnapshot() {
        Desafio challenge = challenge(false);
        Clock moving = mock(Clock.class);
        DynamoDesafioStore timed = new DynamoDesafioStore(dynamo, "auth-test", hasher, moving);
        read(challenge, 0, false);
        when(moving.instant()).thenReturn(NOW, NOW.plusSeconds(300));
        assertThat(timed.verificarEConsumir(challenge.id(), "123456", NOW)).isEmpty();
        verify(dynamo, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
        when(moving.instant()).thenReturn(NOW, NOW, NOW.plusSeconds(300));
        assertThat(timed.verificarEConsumir(challenge.id(), "123456", NOW)).isEmpty();
        verify(dynamo).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test void sourceBucketAndCooldownUseCurrentClockAfterSlowLookup() {
        Clock later = Clock.fixed(NOW.plusMillis(1), ZoneOffset.UTC);
        var timed = new DynamoDesafioStore(dynamo, "auth-test", hasher, later);
        assertThat(timed.emitir(challenge(true), "source", NOW.minusMillis(1))).isTrue();
        var items = transaction().transactItems();
        assertThat(items.get(2).update().key().get("PK").s()).isEqualTo("source#source#" + NOW.getEpochSecond() / 300);
        assertThat(items.get(1).update().expressionAttributeValues().get(":next").n())
                .isEqualTo(Long.toString(NOW.plusSeconds(60).plusMillis(1).toEpochMilli()));
    }

    @Test void challengeThatExpiredDuringLookupIsNeverIssued() {
        var timed = new DynamoDesafioStore(dynamo, "auth-test", hasher, Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC));
        assertThatThrownBy(() -> timed.emitir(challenge(true), "source", NOW))
                .isInstanceOf(IllegalStateException.class).hasMessage("Expired challenge issuance");
        verifyNoInteractions(dynamo);
    }

    @Test void dummyCanBeConsumedButItsSnapshotNeverContainsACustomer() {
        Desafio challenge = challenge(true);
        read(challenge, 0, false);
        assertThat(store.verificarEConsumir(challenge.id(), "123456", NOW).orElseThrow().clienteId()).isNull();
    }

    @Test void inconsistentIssuanceReferenceAndNegativeAttemptsFailClosed() {
        Desafio challenge = challenge(false);
        var mismatched = item(challenge, 0, false);
        mismatched.put("currentChallenge", s(UUID.randomUUID().toString()));
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(
                GetItemResponse.builder().item(mismatched).build(),
                GetItemResponse.builder().item(item(challenge, -1, false)).build());
        assertThat(store.verificarEConsumir(challenge.id(), "123456", NOW)).isEmpty();
        assertThat(store.verificarEConsumir(challenge.id(), "123456", NOW)).isEmpty();
        verify(dynamo, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    private Desafio challenge(boolean dummy) {
        byte[] salt = new byte[16];
        return new Desafio(UUID.randomUUID(), "cpf-hash", dummy ? null : UUID.randomUUID(),
                dummy ? 0 : 1, salt, hasher.hash("123456", salt), NOW.plusSeconds(300));
    }
    private void read(Desafio challenge, int attempts, boolean consumed) {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item(challenge, attempts, consumed)).build());
    }
    private Map<String, AttributeValue> item(Desafio c, int attempts, boolean consumed) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("cpfHash", s(c.cpfHash()));
        item.put("currentChallenge", s(c.id().toString()));
        if (c.clienteId() != null) item.put("clienteId", s(c.clienteId().toString()));
        item.put("versaoIdentidade", n(c.versaoIdentidade()));
        item.put("salt", AttributeValue.builder().b(SdkBytes.fromByteArray(c.salt())).build());
        item.put("otpHash", AttributeValue.builder().b(SdkBytes.fromByteArray(c.hash())).build());
        item.put("expiresAt", n(c.expiraEm().toEpochMilli()));
        item.put("attempts", n(attempts));
        item.put("consumed", AttributeValue.builder().bool(consumed).build());
        return item;
    }
    private TransactWriteItemsRequest transaction() {
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(capture.capture());
        return capture.getValue();
    }
    private static TransactionCanceledException cancel(String... reasons) {
        return TransactionCanceledException.builder().cancellationReasons(Arrays.stream(reasons)
                .map(code -> CancellationReason.builder().code(code).build()).toList()).build();
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue n(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}
