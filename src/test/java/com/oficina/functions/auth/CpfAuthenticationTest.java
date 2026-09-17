package com.oficina.functions.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.functions.support.InMemoryDesafioStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CpfAuthenticationTest {
    private static final String CPF = "39053344705", CODE = "123456";
    private static final Instant START = Instant.parse("2026-09-15T12:00:00Z");

    @Test void challengeIsConsumableOnlyOnce() {
        Fixture f = new Fixture();
        EmissaoDesafio issued = f.issue();
        assertThat(f.verify(issued)).isEqualTo(new TokenResposta("signed-token", "Bearer", 900));
        failure(() -> f.verify(issued), 401);
        assertThat(f.signed.get()).isEqualTo(1);
    }

    @Test void normalizedCpfRegisteredDestinationPersistFirstAndGenericAcknowledgement() {
        Fixture f = new Fixture();
        EmissaoDesafio issued = f.issue();
        assertThat(f.queriedCpf).isEqualTo(CPF);
        assertThat(f.sent).containsExactly("registered@example.invalid:" + CODE);
        Desafio snapshot = f.state.snapshot(issued.desafioId()).orElseThrow();
        assertThat(snapshot.cpfHash()).hasSize(64).doesNotContain(CPF);
        assertThat(f.sourceHash).hasSize(64).doesNotContain("fixture-source");
        assertThat(snapshot.expiraEm()).isEqualTo(START.plusSeconds(300));
        assertThat(snapshot.clienteId()).isEqualTo(f.customer.id());
        assertThat(snapshot.versaoIdentidade()).isEqualTo(1);
        var json = new ObjectMapper().valueToTree(issued);
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get("desafioId").asText()).isEqualTo(issued.desafioId().toString());
        assertThat(json.get("expiraEmSegundos").asInt()).isEqualTo(300);
        assertThat(json.toString()).doesNotContain(CPF, f.customer.id().toString(), "registered@", CODE, "token", "email");
    }

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", "11111111111", "00000000000", "39053344704", "39053344715",
            "12345678901", "11222333000181", "abc39053344705", "３９０５３３４４７０５", "390 533 44705"})
    void invalidCpfIsRejectedBeforeLookup(String cpf) {
        Fixture f = new Fixture();
        failure(() -> f.criar.executar(cpf, "fixture-source"), 400);
        assertThat(f.queriedCpf).isNull();
        assertThat(f.state.size()).isZero();
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " "})
    void rejectMissingSource(String source) {
        Fixture f = new Fixture();
        failure(() -> f.criar.executar(CPF, source), 400);
    }

    @Test void knownUnknownInactiveShareAcknowledgementAndCooldownDummyNeverSigns() {
        for (int mode = 0; mode < 3; mode++) {
            Fixture f = new Fixture();
            if (mode == 1) f.customer = null;
            if (mode == 2) f.customer = f.snapshot(false, 1);
            EmissaoDesafio issued = f.issue();
            assertThat(issued.expiraEmSegundos()).isEqualTo(300);
            failure(f::issue, 429);
            f.clock.advance(59);
            failure(f::issue, 429);
            if (mode > 0) {
                assertThat(f.state.snapshot(issued.desafioId()).orElseThrow().clienteId()).isNull();
                failure(() -> f.verify(issued), 401);
                assertThat(f.sent).isEmpty();
                assertThat(f.signed.get()).isZero();
            }
            f.clock.advance(1);
            assertThat(f.issue().desafioId()).isNotEqualTo(issued.desafioId());
        }
    }

    @Test void exactExpiryBoundary() {
        Fixture before = new Fixture();
        EmissaoDesafio valid = before.issue();
        before.clock.now = START.plusSeconds(300).minusNanos(1);
        assertThat(before.verify(valid).accessToken()).isEqualTo("signed-token");
        Fixture at = new Fixture();
        EmissaoDesafio expired = at.issue();
        at.clock.advance(300);
        failure(() -> at.verify(expired), 401);
        assertThat(at.signed.get()).isZero();
    }

    @Test void fifthWrongExhaustsButFifthCorrectSucceeds() {
        for (boolean exhausted : new boolean[] {true, false}) {
            Fixture f = new Fixture();
            EmissaoDesafio issued = f.issue();
            for (int i = 0; i < (exhausted ? 5 : 4); i++)
                failure(() -> f.verificar.executar(issued.desafioId(), "999999"), 401);
            if (exhausted) failure(() -> f.verify(issued), 401);
            else assertThat(f.verify(issued).accessToken()).isEqualTo("signed-token");
        }
    }

    @Test void malformedCodesSpendAttempts() {
        Fixture f = new Fixture();
        EmissaoDesafio issued = f.issue();
        for (String invalid : Arrays.asList(null, "", "12345", "1234567", "abcdef"))
            failure(() -> f.verificar.executar(issued.desafioId(), invalid), 401);
        failure(() -> f.verify(issued), 401);
        failure(() -> f.verificar.executar(null, CODE), 401);
        failure(() -> f.verificar.executar(UUID.randomUUID(), CODE), 401);
    }

    @Test void resendInvalidatesPriorAndRejectedResendDoesNot() {
        Fixture f = new Fixture();
        EmissaoDesafio first = f.issue();
        failure(f::issue, 429);
        assertThat(f.verify(first).accessToken()).isEqualTo("signed-token");
        f.clock.advance(60);
        EmissaoDesafio second = f.issue();
        f.clock.advance(60);
        EmissaoDesafio third = f.issue();
        failure(() -> f.verify(second), 401);
        assertThat(f.verify(third).accessToken()).isEqualTo("signed-token");
    }

    @Test void simultaneousVerificationHasOneWinner() throws Exception {
        Fixture f = new Fixture();
        EmissaoDesafio issued = f.issue();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> verify = () -> {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            try { f.verify(issued); return 200; }
            catch (AutenticacaoException ex) { return ex.status(); }
        };
        try {
            Future<Integer> first = executor.submit(verify), second = executor.submit(verify);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
            assertThat(f.signed.get()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void freshLookupRejectsMissingInactiveRotatedInvalidAndMismatchedIdentity() {
        for (int mode = 0; mode < 5; mode++) {
            Fixture f = new Fixture();
            EmissaoDesafio issued = f.issue();
            f.customer = switch (mode) {
                case 0 -> null;
                case 1 -> f.snapshot(false, 1);
                case 2 -> f.snapshot(true, 2);
                case 3 -> f.snapshot(true, 0);
                default -> new ClienteSnapshot(UUID.randomUUID(), CPF, true, "other@example.invalid", 1);
            };
            failure(() -> f.verify(issued), 401);
            failure(() -> f.verify(issued), 401);
            assertThat(f.idQueries).isEqualTo(1);
            assertThat(f.signed.get()).isZero();
        }
    }

    @Test void signerReceivesFreshSnapshotAndCurrentTime() {
        Fixture f = new Fixture();
        EmissaoDesafio issued = f.issue();
        f.customer = new ClienteSnapshot(f.customer.id(), CPF, true, "current@example.invalid", 1);
        f.clock.advance(10);
        f.verify(issued);
        assertThat(f.signedCustomer).isSameAs(f.customer);
        assertThat(f.signedAt).isEqualTo(START.plusSeconds(10));
    }

    @Test void emailFailureInvalidatesAndPreservesCooldown() {
        Fixture f = new Fixture();
        f.failEmail = true;
        failure(f::issue, 503);
        failure(() -> f.verificar.executar(f.lastIssued.id(), CODE), 401);
        failure(f::issue, 429);
        assertThat(f.signed.get()).isZero();
    }

    @Test void invalidationFailureRemainsSanitized503() {
        Fixture f = new Fixture();
        f.failEmail = f.failInvalidate = true;
        failure(f::issue, 503);
        assertThat(f.invalidateCalls).isEqualTo(1);
    }

    @Test void issuanceDependenciesReturnSafe503() {
        for (int mode = 0; mode < 3; mode++) {
            Fixture f = new Fixture();
            if (mode == 0) f.failCpf = true;
            if (mode == 1) f.failEmit = true;
            if (mode == 2) f.badGenerator = true;
            failure(f::issue, 503);
            assertThat(f.sent).isEmpty();
        }
    }

    @Test void verificationDependenciesFailClosedAndCommittedConsumptionCannotBeRetried() {
        for (int mode = 0; mode < 4; mode++) {
            Fixture f = new Fixture();
            EmissaoDesafio issued = f.issue();
            if (mode == 0) f.failVerify = true;
            if (mode == 1) f.failId = true;
            if (mode == 2) f.failSign = true;
            if (mode == 3) f.emptyToken = true;
            failure(() -> f.verify(issued), 503);
            f.failVerify = f.failId = f.failSign = f.emptyToken = false;
            if (mode > 0) {
                failure(() -> f.verify(issued), 401);
                failure(f::issue, 429);
                f.clock.advance(60);
                assertThat(f.verify(f.issue()).accessToken()).isEqualTo("signed-token");
            }
        }
    }

    @Test void sharedSourceBucketCapsKnownAndUnknownAndResets() {
        for (boolean known : new boolean[] {true, false}) {
            Fixture f = new Fixture();
            ClienteSnapshot original = f.customer;
            for (int i = 0; i < 10; i++) {
                String cpf = generatedCpf(i);
                f.customer = known ? new ClienteSnapshot(original.id(), cpf, true, original.email(), 1) : null;
                f.criar.executar(cpf, "shared-source");
            }
            String blockedCpf = generatedCpf(10);
            f.customer = known ? new ClienteSnapshot(original.id(), blockedCpf, true, original.email(), 1) : null;
            failure(() -> f.criar.executar(blockedCpf, "shared-source"), 429);
            f.clock.advance(300);
            assertThat(f.criar.executar(blockedCpf, "shared-source").expiraEmSegundos()).isEqualTo(300);
        }
    }

    @Test void recordStringsDoNotLeak() {
        Fixture f = new Fixture();
        EmissaoDesafio issued = f.issue();
        assertThat(f.customer.toString()).doesNotContain(CPF, "registered@", f.customer.id().toString());
        assertThat(f.state.snapshot(issued.desafioId()).orElseThrow().toString()).doesNotContain(CPF, f.sourceHash);
        assertThat(f.verify(issued).toString()).doesNotContain("signed-token");
    }

    private static String generatedCpf(int index) {
        String digits = String.format(Locale.ROOT, "%09d", 123456700 + index);
        for (int size = 9; size <= 10; size++) {
            int sum = 0;
            for (int i = 0; i < size; i++) sum += (digits.charAt(i) - '0') * (size + 1 - i);
            int check = 11 - sum % 11;
            digits += check >= 10 ? 0 : check;
        }
        return digits;
    }

    private static void failure(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AutenticacaoException.class, ex -> {
            assertThat(ex.status()).isEqualTo(status);
            if (status == 401) assertThat(ex.codigo()).isEqualTo("DESAFIO_INVALIDO");
            if (status == 429) assertThat(ex.codigo()).isEqualTo("LIMITE_EXCEDIDO");
            if (status == 503) assertThat(ex.codigo()).isEqualTo("SERVICO_INDISPONIVEL");
            assertThat(ex.getMessage()).isEqualTo(ex.codigo()).doesNotContain("sensitive");
            assertThat(ex.getCause()).isNull();
            assertThat(ex.getSuppressed()).isEmpty();
        });
    }

    private static final class MutableClock extends Clock {
        private Instant now = START;
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final OtpHasher hasher = new OtpHasher();
        final InMemoryDesafioStore state = new InMemoryDesafioStore(hasher);
        final AtomicInteger signed = new AtomicInteger();
        final List<String> sent = new ArrayList<>();
        ClienteSnapshot customer = new ClienteSnapshot(UUID.randomUUID(), CPF, true, "registered@example.invalid", 1);
        ClienteSnapshot signedCustomer;
        Instant signedAt;
        String queriedCpf, sourceHash;
        Desafio lastIssued;
        int idQueries, invalidateCalls;
        boolean failCpf, failId, failEmit, failVerify, failEmail, failInvalidate, failSign, emptyToken, badGenerator;
        final ClienteLookup lookup = new ClienteLookup() {
            public Optional<ClienteSnapshot> porCpf(String cpf) {
                queriedCpf = cpf;
                if (failCpf) throw dependency();
                return Optional.ofNullable(customer);
            }
            public Optional<ClienteSnapshot> porId(UUID id) {
                idQueries++;
                if (failId) throw dependency();
                return Optional.ofNullable(customer);
            }
        };
        final DesafioStore store = new DesafioStore() {
            public boolean emitir(Desafio desafio, String source, Instant now) {
                if (failEmit) throw dependency();
                lastIssued = desafio;
                sourceHash = source;
                return state.emitir(desafio, source, now);
            }
            public Optional<Desafio> verificarEConsumir(UUID id, String code, Instant now) {
                if (failVerify) throw dependency();
                return state.verificarEConsumir(id, code, now);
            }
            public void invalidar(UUID id) {
                invalidateCalls++;
                if (failInvalidate) throw dependency();
                state.invalidar(id);
            }
        };
        final CriarDesafio criar = new CriarDesafio(lookup, store, (email, code) -> {
            assertThat(state.snapshot(lastIssued.id())).isPresent();
            if (failEmail) throw dependency();
            sent.add(email + ":" + code);
        }, () -> badGenerator ? "bad-code" : CODE, hasher, clock);
        final VerificarDesafio verificar = new VerificarDesafio(lookup, store, (current, now) -> {
            if (failSign) throw dependency();
            signed.incrementAndGet();
            signedCustomer = current;
            signedAt = now;
            return emptyToken ? "" : "signed-token";
        }, clock);
        EmissaoDesafio issue() { return criar.executar("390.533.447-05", "fixture-source"); }
        TokenResposta verify(EmissaoDesafio issued) { return verificar.executar(issued.desafioId(), CODE); }
        ClienteSnapshot snapshot(boolean active, long version) {
            return new ClienteSnapshot(customer.id(), CPF, active, customer.email(), version);
        }
        static RuntimeException dependency() {
            return new IllegalStateException("sensitive CPF/email/code/source dependency detail");
        }
    }
}
