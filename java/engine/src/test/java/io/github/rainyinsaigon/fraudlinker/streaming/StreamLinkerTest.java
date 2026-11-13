package io.github.rainyinsaigon.fraudlinker.streaming;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.normalize.Normalizer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static io.github.rainyinsaigon.fraudlinker.Fixtures.assertValidCompletePartition;
import static io.github.rainyinsaigon.fraudlinker.Fixtures.clustersByAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamLinkerTest {

    private static LinkState state(List<Account> accounts, List<Constraint> constraints) {
        LinkState state = BatchLinker.build(accounts, Constraints.of(constraints), ClusteringConfig.defaults());
        assertValidCompletePartition(state);
        return state;
    }

    private static Constraint distinct(String a, String b) {
        return new Constraint(a, b, Constraints.VERIFIED_DISTINCT);
    }

    @Test
    void constraintOnAFutureAccountIdIsHonoured() {
        Instant base = Instant.parse("2026-08-10T10:00:00Z");
        LinkState state = state(List.of(
                account("acc_existing", "same.person@gmail.com", "shared-device", "10.1.1.1", "shared-payment", base)
        ), List.of(distinct("acc_existing", "acc_future")));
        String existingCluster = clustersByAccount(state).get("acc_existing");

        Assignment assignment = new StreamLinker(state).assign(account("acc_future", "sameperson+promo@gmail.com",
                "shared-device", "10.1.1.1", "shared-payment", base.plusSeconds(60)));

        assertThat(assignment.clusterId()).isNotEqualTo(existingCluster);
        assertValidCompletePartition(state);
    }

    @Test
    void duplicateIsRejectedWithoutChangingState() {
        Instant base = Instant.parse("2026-08-11T10:00:00Z");
        LinkState state = state(List.of(account("acc_existing", "existing@example.com", "", "10.1.1.1", "", base)), List.of());
        StreamLinker linker = new StreamLinker(state);
        Account incoming = account("acc_new", "new@example.com", "", "20.1.1.1", "", base.plus(Duration.ofHours(1)));
        linker.assign(incoming);

        int beforeAccounts = state.store().size();
        int beforeDsu = state.dsu().size();
        ClustersOutput beforeClusters = state.clusters();

        assertThatThrownBy(() -> linker.assign(incoming))
                .isInstanceOf(LinkerException.class)
                .hasMessageContaining("duplicate account_id");
        assertThat(state.store().size()).isEqualTo(beforeAccounts);
        assertThat(state.dsu().size()).isEqualTo(beforeDsu);
        assertThat(state.clusters()).isEqualTo(beforeClusters);
        assertValidCompletePartition(state);
    }

    @Test
    void fallsBackToTheBestClusterItIsAllowedToJoin() {
        Instant base = Instant.parse("2026-08-12T10:00:00Z");
        LinkState state = state(List.of(
                account("acc_best", "winner@gmail.com", "", "10.1.1.1", "", base),
                account("acc_fallback", "fallback@example.com", "", "20.1.1.1", "fallback-payment", base)
        ), List.of(distinct("acc_incoming", "acc_best")));
        Map<String, String> before = clustersByAccount(state);

        Assignment assignment = new StreamLinker(state).assign(account("acc_incoming", "winner@gmail.com", "",
                "30.1.1.1", "fallback-payment", base.plusSeconds(60)));

        assertThat(assignment.clusterId()).isEqualTo(before.get("acc_fallback"));
        Map<String, String> after = clustersByAccount(state);
        assertThat(after.get("acc_incoming")).isNotEqualTo(after.get("acc_best"));
        assertValidCompletePartition(state);
    }

    @Test
    void assignmentIsMonotonicAndClusterIdsStayStable() {
        Instant base = Instant.parse("2026-08-13T10:00:00Z");
        LinkState state = state(List.of(
                account("acc_a", "preferred@gmail.com", "", "10.1.1.1", "", base),
                account("acc_b", "other@example.com", "", "20.1.1.1", "secondary-payment", base)
        ), List.of());
        StreamLinker linker = new StreamLinker(state);
        Map<String, String> original = clustersByAccount(state);
        assertThat(original.get("acc_a")).isNotEqualTo(original.get("acc_b"));

        Assignment matched = linker.assign(account("acc_matches_both", "preferred@gmail.com", "", "30.1.1.1",
                "secondary-payment", base.plusSeconds(60)));
        assertThat(matched.clusterId()).as("joins the stronger cluster").isEqualTo(original.get("acc_a"));
        Map<String, String> afterMatch = clustersByAccount(state);
        assertThat(afterMatch.get("acc_a")).isEqualTo(original.get("acc_a"));
        assertThat(afterMatch.get("acc_b")).isEqualTo(original.get("acc_b"));
        assertThat(afterMatch.get("acc_a")).as("never merges two old clusters").isNotEqualTo(afterMatch.get("acc_b"));

        Assignment singleton = linker.assign(account("acc_singleton", "brand-new@example.net", "", "40.1.1.1", "",
                base.plus(Duration.ofHours(2))));
        assertThat(singleton.clusterId()).isNotIn(original.get("acc_a"), original.get("acc_b"));
        assertThat(singleton.confidence()).isEqualTo(1.0);

        Assignment joined = linker.assign(account("acc_singleton_alias", "brand-new@example.net", "", "50.1.1.1", "",
                base.plus(Duration.ofHours(3))));
        assertThat(joined.clusterId()).isEqualTo(singleton.clusterId());

        Map<String, String> last = clustersByAccount(state);
        assertThat(last.get("acc_a")).isEqualTo(original.get("acc_a"));
        assertThat(last.get("acc_b")).isEqualTo(original.get("acc_b"));
        assertValidCompletePartition(state);
    }

    @Test
    void projectedVelocityJoinsOneClusterWithoutMergingOldOnes() {
        Instant base = Instant.parse("2026-08-14T10:00:00Z");
        LinkState state = state(List.of(
                account("acc_a", "a@example.com", "", "194.181.67.10", "", base),
                account("acc_b", "b@example.com", "", "194.181.67.20", "", base.plus(Duration.ofMinutes(10)))
        ), List.of());
        Map<String, String> before = clustersByAccount(state);
        assertThat(before.get("acc_a")).as("two accounts do not meet the velocity minimum").isNotEqualTo(before.get("acc_b"));

        Assignment assignment = new StreamLinker(state).assign(account("acc_c", "c@example.com", "", "194.181.67.30", "",
                base.plus(Duration.ofMinutes(20))));

        assertThat(assignment.clusterId()).as("deterministic lower cluster on a tie").isEqualTo(before.get("acc_a"));
        Map<String, String> after = clustersByAccount(state);
        assertThat(after.get("acc_a")).isEqualTo(before.get("acc_a"));
        assertThat(after.get("acc_b")).isEqualTo(before.get("acc_b"));
        assertThat(after.get("acc_a")).isNotEqualTo(after.get("acc_b"));
        assertThat(after.get("acc_c")).isEqualTo(after.get("acc_a"));
        assertValidCompletePartition(state);
    }

    @Test
    void projectedDistinctIpRatioIsZeroForOversizedPrefix() {
        Instant base = Instant.parse("2026-08-14T10:00:00Z");
        List<Account> initial = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            initial.add(account("acc_prefix_" + i, "person-" + i + "@example.com", "", "203.0.113." + (i + 1), "",
                    base.plus(Duration.ofMinutes(i))));
        }
        LinkState state = state(initial, List.of());
        Features incoming = Normalizer.normalize(account("acc_incoming", "incoming@example.net", "", "203.0.113.99", "",
                base.plus(Duration.ofMinutes(9))));
        int projectedSize = state.store().frequencies().ipPrefix(incoming.ipPrefix()) + 1;

        assertThat(new StreamLinker(state).projectedDistinctIpRatio(incoming, projectedSize)).isZero();
    }

    @Test
    void prefixWithTwoEstablishedIdentitiesIsNotExpanded() {
        Instant base = Instant.parse("2026-08-15T10:00:00Z");
        LinkState state = state(List.of(
                account("acc_a1", "a1@example.com", "", "203.0.113.10", "payment-a", base),
                account("acc_a2", "a2@example.com", "", "203.0.113.20", "payment-a", base.plus(Duration.ofDays(10))),
                account("acc_b1", "b1@example.com", "", "203.0.113.30", "payment-b", base.plus(Duration.ofDays(20))),
                account("acc_b2", "b2@example.com", "", "198.51.100.30", "payment-b", base.plus(Duration.ofDays(30)))
        ), List.of());
        Map<String, String> before = clustersByAccount(state);

        Assignment assignment = new StreamLinker(state).assign(account("acc_incoming", "incoming@example.net", "",
                "203.0.113.40", "", base.plus(Duration.ofDays(40))));

        assertThat(assignment.clusterId()).isNotIn(before.get("acc_a1"), before.get("acc_b1"));
        assertValidCompletePartition(state);
    }

    @Test
    void rejectsMissingIdOrTimestamp() {
        LinkState state = state(List.of(), List.of());
        StreamLinker linker = new StreamLinker(state);
        assertThatThrownBy(() -> linker.assign(new Account("  ", "a@b.com", null, "", null, Instant.EPOCH)))
                .hasMessageContaining("missing account_id");
        assertThatThrownBy(() -> linker.assign(new Account("a", "a@b.com", null, "", null, null)))
                .hasMessageContaining("created_at");
        assertThat(state.store().size()).isZero();
    }

    @Test
    void assignmentStaysFastWithTenThousandAccounts() {
        Instant base = Instant.parse("2026-08-01T00:00:00Z");
        List<Account> initial = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            initial.add(account("history_" + i, "history-" + i + "@example.com", "",
                    "10.%d.%d.%d".formatted((i / 65536) % 256, (i / 256) % 256, i % 256), "", base.plusSeconds(i)));
        }
        StreamLinker linker = new StreamLinker(BatchLinker.build(initial, Constraints.empty(), ClusteringConfig.defaults()));

        int events = 2_000;
        long start = System.nanoTime();
        for (int i = 0; i < events; i++) {
            linker.assign(account("event_" + i, "event-" + i + "@example.net", "",
                    "198.18.%d.%d".formatted((i / 256) % 256, i % 256), "", base.plusSeconds(i)));
        }
        double averageMillis = (System.nanoTime() - start) / 1e6 / events;
        // Generous bound so slow CI machines pass; real cost is microseconds per event.
        assertThat(averageMillis).isLessThan(5.0);
    }
}
