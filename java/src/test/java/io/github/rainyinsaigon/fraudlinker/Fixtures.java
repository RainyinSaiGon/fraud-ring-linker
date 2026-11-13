package io.github.rainyinsaigon.fraudlinker;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.domain.Features;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Shared test helpers. */
public final class Fixtures {
    private Fixtures() {
    }

    /** An account; empty device or payment means "not provided" (null). */
    public static Account account(String id, String email, String device, String ip, String payment, Instant createdAt) {
        return new Account(id, email, device.isEmpty() ? null : device, ip, payment.isEmpty() ? null : payment, createdAt);
    }

    public static Account account(String id, String email, Instant createdAt) {
        return new Account(id, email, null, "", null, createdAt);
    }

    /** Builder for hand-made feature vectors where most signals are absent. */
    public static FeaturesBuilder features() {
        return new FeaturesBuilder();
    }

    public static final class FeaturesBuilder {
        private String emailExact = "";
        private String emailCanonical = "";
        private String emailBaseLocal = "";
        private String emailSkeleton = "";
        private String emailDomain = "";
        private String device = "";
        private String payment = "";
        private String ip;
        private String ipPrefix;
        private Instant createdAt;

        public FeaturesBuilder email(String exact, String canonical, String baseLocal, String skeleton, String domain) {
            this.emailExact = exact;
            this.emailCanonical = canonical;
            this.emailBaseLocal = baseLocal;
            this.emailSkeleton = skeleton;
            this.emailDomain = domain;
            return this;
        }

        public FeaturesBuilder device(String value) {
            this.device = value;
            return this;
        }

        public FeaturesBuilder payment(String value) {
            this.payment = value;
            return this;
        }

        public FeaturesBuilder ip(String address, String prefix) {
            this.ip = address;
            this.ipPrefix = prefix;
            return this;
        }

        public FeaturesBuilder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public Features build() {
            return new Features(emailExact, emailCanonical, emailBaseLocal, emailBaseLocal, emailSkeleton,
                    emailDomain, device, payment, ip, ipPrefix, createdAt);
        }
    }

    /** Maps each account ID to its cluster ID, failing if an account appears twice. */
    public static Map<String, String> clustersByAccount(LinkState state) {
        Map<String, String> result = new HashMap<>();
        for (Cluster cluster : state.clusters().clusters()) {
            for (String accountId : cluster.accountIds()) {
                String previous = result.put(accountId, cluster.clusterId());
                if (previous != null) {
                    fail("account %s appears in clusters %s and %s", accountId, previous, cluster.clusterId());
                }
            }
        }
        return result;
    }

    /** Asserts the hard contract: invariants hold and every account is in exactly one sorted cluster. */
    public static void assertValidCompletePartition(LinkState state) {
        state.validateInvariants();
        Map<String, String> byAccount = clustersByAccount(state);
        for (Cluster cluster : state.clusters().clusters()) {
            assertThat(cluster.clusterId()).isNotEmpty();
            assertThat(cluster.confidence()).isBetween(0.0, 1.0);
            assertThat(cluster.accountIds()).isSorted();
        }
        assertThat(byAccount).hasSize(state.store().size());
        for (int i = 0; i < state.store().size(); i++) {
            assertThat(byAccount).containsKey(state.store().account(i).accountId());
        }
    }
}
