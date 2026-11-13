package io.github.rainyinsaigon.fraudlinker.index;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.normalize.Normalizer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");

    private static IndexConfig config(int emailLimit, int ipLimit, int prefixLimit, int signatureLimit) {
        IndexConfig d = IndexConfig.defaults();
        return new IndexConfig(emailLimit, d.deviceBlockLimit(), d.paymentBlockLimit(), ipLimit, prefixLimit,
                signatureLimit, d.maxEmailRunes(), d.maxCandidates());
    }

    private static void add(SignalStore store, Account account) {
        store.add(account, Normalizer.normalize(account));
    }

    @Test
    void hotEmailPostingCreatesNoCandidatePairs() {
        IndexConfig d = IndexConfig.defaults();
        SignalStore store = new SignalStore(config(2, d.ipBlockLimit(), d.prefixBlockLimit(), 2));
        String[] emails = {"john.doe@gmail.com", "johndoe+one@gmail.com", "j.o.h.n.d.o.e+two@googlemail.com"};
        for (int i = 0; i < emails.length; i++) {
            add(store, account("acc_" + i, emails[i], T0));
        }
        assertThat(store.candidatePairs()).isEmpty();
    }

    @Test
    void projectedSizeCountsTheIncomingAccountAgainstNetworkLimits() {
        SignalStore store = new SignalStore(config(64, 2, 2, 32));
        add(store, account("acc_0", "one@one.example", "", "203.0.113.25", "", T0));
        add(store, account("acc_1", "two@two.example", "", "203.0.113.25", "", T0));
        Features incoming = Normalizer.normalize(account("acc_in", "three@three.example", "", "203.0.113.25", "", T0));

        assertThat(store.candidatesFor(incoming, false)).hasSize(2);
        assertThat(store.candidatesFor(incoming, true)).isEmpty();
    }

    @Test
    void hotNetworkPostingCreatesNoCandidatePairs() {
        SignalStore store = new SignalStore(config(64, 2, 2, 32));
        for (int i = 0; i < 3; i++) {
            add(store, account("acc_" + i, "person@domain-" + i + ".example", "", "198.51.100.10", "", T0));
        }
        assertThat(store.candidatePairs()).isEmpty();
    }

    @Test
    void candidatesAreSortedByAccountId() {
        SignalStore store = new SignalStore(IndexConfig.defaults());
        add(store, account("acc_z", "x@example.com", "", "", "pay", T0));
        add(store, account("acc_a", "y@example.com", "", "", "pay", T0));
        Features query = Normalizer.normalize(account("q", "q@example.com", "", "", "pay", T0));
        assertThat(store.candidatesFor(query, false)).containsExactly(1, 0);
    }

    @Test
    void rejectsDuplicateAccountId() {
        SignalStore store = new SignalStore(IndexConfig.defaults());
        add(store, account("acc_a", "a@example.com", T0));
        assertThatThrownBy(() -> add(store, account("acc_a", "b@example.com", T0)))
                .isInstanceOf(LinkerException.class)
                .hasMessageContaining("duplicate account_id");
    }

    @Test
    void burstSizeCountsAccountsInsideOneWindowThatHoldsBothEndpoints() {
        SignalStore store = new SignalStore(IndexConfig.defaults());
        for (int i = 0; i < 4; i++) {
            add(store, account("acc_" + i, i + "@x.com", "", "194.181.67." + (10 + i), "", T0.plusSeconds(i * 600L)));
        }
        add(store, account("acc_late", "late@x.com", "", "194.181.67.99", "", T0.plus(Duration.ofHours(5))));

        Features first = store.features(0);
        Features second = store.features(1);
        assertThat(store.burstSize(first, second, Duration.ofHours(1), null)).isEqualTo(4);
        assertThat(store.burstSize(first, store.features(4), Duration.ofHours(1), null)).isZero();
    }

    @Test
    void distinctIpRatioMeasuresIpDiversity() {
        SignalStore store = new SignalStore(IndexConfig.defaults());
        add(store, account("a", "a@x.com", "", "10.0.0.1", "", T0));
        add(store, account("b", "b@x.com", "", "10.0.0.1", "", T0));
        add(store, account("c", "c@x.com", "", "10.0.0.2", "", T0));
        add(store, account("d", "d@x.com", "", "10.0.0.3", "", T0));
        assertThat(store.distinctIpRatio(store.prefixPosting("10.0.0.0/24"))).isEqualTo(0.75);
        assertThat(store.distinctIpRatio(java.util.List.of())).isZero();
    }
}
