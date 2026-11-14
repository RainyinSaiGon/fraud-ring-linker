package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.index.IndexConfig;
import io.github.rainyinsaigon.fraudlinker.index.SignalStore;
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

class BatchLinkerTest {

    private static LinkState build(List<Account> accounts, List<Constraint> constraints) {
        return BatchLinker.build(accounts, Constraints.of(constraints), ClusteringConfig.defaults());
    }

    private static Constraint distinct(String a, String b) {
        return new Constraint(a, b, Constraints.VERIFIED_DISTINCT);
    }

    @Test
    void directConstraintWinsOverStrongEvidence() {
        Instant base = Instant.parse("2026-08-01T10:00:00Z");
        LinkState state = build(List.of(
                account("acc_a", "same.person+one@gmail.com", "shared-device", "10.0.0.1", "shared-payment", base),
                account("acc_b", "sameperson@gmail.com", "shared-device", "10.0.0.1", "shared-payment", base.plusSeconds(60))
        ), List.of(distinct("acc_a", "acc_b")));

        assertValidCompletePartition(state);
        Map<String, String> clusters = clustersByAccount(state);
        assertThat(clusters.get("acc_a")).isNotEqualTo(clusters.get("acc_b"));
    }

    @Test
    void emptyInputProducesEmptyPartition() {
        LinkState state = build(List.of(), List.of());
        state.validateInvariants();
        assertThat(state.clusters().clusters()).isEmpty();
    }

    @Test
    void transitiveConstraintKeepsTheStrongerSideOfABridge() {
        // A and X share an exact email (strong). X and B share a device plus a
        // fuzzy email (weaker). A and B are verified distinct, so the weaker
        // bridge must be the link that is cut.
        Instant base = Instant.parse("2026-08-02T10:00:00Z");
        LinkState state = build(List.of(
                account("acc_a", "anchor@example.com", "", "10.1.1.1", "", base),
                account("acc_x", "anchor@example.com", "bridge-device", "20.1.1.1", "", base.plusSeconds(60)),
                account("acc_b", "anch0r@example.com", "bridge-device", "30.1.1.1", "", base.plusSeconds(120))
        ), List.of(distinct("acc_a", "acc_b")));

        assertValidCompletePartition(state);
        Map<String, String> clusters = clustersByAccount(state);
        assertThat(clusters.get("acc_a")).isEqualTo(clusters.get("acc_x"));
        assertThat(clusters.get("acc_a")).isNotEqualTo(clusters.get("acc_b"));
    }

    @Test
    void canMergeChecksConstraintsOnEveryMemberNotJustTheEndpoints() {
        Instant base = Instant.parse("2026-08-03T10:00:00Z");
        SignalStore store = new SignalStore(IndexConfig.defaults());
        for (String id : List.of("acc_a", "acc_x", "acc_b", "acc_y")) {
            Account account = account(id, id + "@example.com", base);
            store.add(account, io.github.rainyinsaigon.fraudlinker.normalize.Normalizer.normalize(account));
        }
        Constraints constraints = Constraints.of(List.of(distinct("acc_a", "acc_y")));
        DisjointSet dsu = new DisjointSet(4);
        dsu.union(0, 1, 0.96);
        dsu.union(2, 3, 0.94);

        assertThat(dsu.canMerge(1, 2, store, constraints)).isFalse();
        assertThat(dsu.find(0)).isNotEqualTo(dsu.find(2));
    }

    @Test
    void outputIsCompleteAndDeterministicUnderInputPermutation() {
        Instant base = Instant.parse("2026-08-04T10:00:00Z");
        List<Account> accounts = List.of(
                account("acc_z", "z@example.com", "", "10.0.0.1", "", base),
                account("acc_c", "c@example.com", "", "20.0.0.1", "payment-bc", base),
                account("acc_a", "alias.person@gmail.com", "", "30.0.0.1", "", base),
                account("acc_d", "aliasperson+promo@gmail.com", "", "40.0.0.1", "", base),
                account("acc_b", "b@example.com", "", "50.0.0.1", "payment-bc", base));
        List<Account> permuted = List.of(accounts.get(3), accounts.get(0), accounts.get(4), accounts.get(1), accounts.get(2));

        LinkState first = build(accounts, List.of());
        LinkState second = build(permuted, List.of());
        assertValidCompletePartition(first);
        assertValidCompletePartition(second);

        ClustersOutput firstOutput = first.clusters();
        assertThat(first.clusters()).isEqualTo(firstOutput);
        assertThat(second.clusters()).isEqualTo(firstOutput);

        Map<String, String> cluster = clustersByAccount(first);
        assertThat(cluster.get("acc_a")).as("canonical email pair").isEqualTo(cluster.get("acc_d"));
        assertThat(cluster.get("acc_b")).as("payment pair").isEqualTo(cluster.get("acc_c"));
        assertThat(cluster.get("acc_z")).isNotIn(cluster.get("acc_a"), cluster.get("acc_b"));
    }

    @Test
    void clusterIdsFollowSmallestMemberId() {
        Instant base = Instant.parse("2026-08-04T10:00:00Z");
        LinkState state = build(List.of(
                account("acc_m", "m@example.com", base),
                account("acc_b", "b@example.com", "", "", "pay", base),
                account("acc_c", "c@example.com", "", "", "pay", base)
        ), List.of());

        List<Cluster> clusters = state.clusters().clusters();
        assertThat(clusters).extracting(Cluster::clusterId).containsExactly("c1", "c2");
        assertThat(clusters.get(0).accountIds()).containsExactly("acc_b", "acc_c");
        assertThat(clusters.get(1).accountIds()).containsExactly("acc_m");
        assertThat(clusters.get(1).confidence()).isEqualTo(1.0);
    }

    @Test
    void hotSharedIpLinksNobody() {
        Instant base = Instant.parse("2026-08-05T10:00:00Z");
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            accounts.add(account("acc_%03d".formatted(i), "unrelated-%03d@example.com".formatted(i), "",
                    "190.236.206.122", "", base.plus(Duration.ofHours(i))));
        }
        LinkState state = build(accounts, List.of());

        assertValidCompletePartition(state);
        assertThat(state.clusters().clusters()).hasSize(64)
                .allSatisfy(cluster -> assertThat(cluster.accountIds()).hasSize(1));
    }

    @Test
    void diversifiedVelocityCohortIsLinked() {
        Instant base = Instant.parse("2026-08-06T10:00:00Z");
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            accounts.add(account("acc_velocity_" + i, "different-" + i + "@example.com", "",
                    "194.181.67." + (i + 10), "", base.plus(Duration.ofMinutes(8L * i))));
        }
        LinkState state = build(accounts, List.of());

        assertValidCompletePartition(state);
        assertThat(state.clusters().clusters()).singleElement()
                .satisfies(cluster -> assertThat(cluster.accountIds()).hasSize(5));
    }

    @Test
    void strongSeedExpandsToItsSmallPrefixCohort() {
        Instant base = Instant.parse("2026-08-07T10:00:00Z");
        LinkState state = build(List.of(
                account("acc_seed_a", "seed-a@example.com", "", "199.59.25.10", "seed-payment", base),
                account("acc_seed_b", "seed-b@example.com", "", "199.59.25.20", "seed-payment", base.plus(Duration.ofDays(10))),
                account("acc_prefix_c", "unrelated-c@example.com", "", "199.59.25.30", "", base.plus(Duration.ofDays(20))),
                account("acc_prefix_d", "unrelated-d@example.com", "", "199.59.25.40", "", base.plus(Duration.ofDays(30)))
        ), List.of());

        assertValidCompletePartition(state);
        assertThat(state.clusters().clusters()).singleElement()
                .satisfies(cluster -> assertThat(cluster.accountIds()).hasSize(4));
    }

    @Test
    void prefixWithTwoEstablishedIdentitiesIsNotExpanded() {
        Instant base = Instant.parse("2026-08-07T10:00:00Z");
        LinkState state = build(List.of(
                account("acc_a1", "a1@example.com", "", "199.59.25.10", "payment-a", base),
                account("acc_a2", "a2@example.com", "", "199.59.25.20", "payment-a", base.plus(Duration.ofDays(10))),
                account("acc_b1", "b1@example.com", "", "199.59.25.30", "payment-b", base.plus(Duration.ofDays(20))),
                account("acc_b2", "b2@example.com", "", "198.51.100.30", "payment-b", base.plus(Duration.ofDays(30))),
                account("acc_unlinked", "unlinked@example.com", "", "199.59.25.40", "", base.plus(Duration.ofDays(40)))
        ), List.of());

        assertValidCompletePartition(state);
        Map<String, String> cluster = clustersByAccount(state);
        assertThat(cluster.get("acc_a1")).isEqualTo(cluster.get("acc_a2"));
        assertThat(cluster.get("acc_b1")).isEqualTo(cluster.get("acc_b2"));
        assertThat(cluster.get("acc_a1")).isNotEqualTo(cluster.get("acc_b1"));
        assertThat(cluster.get("acc_unlinked")).isNotIn(cluster.get("acc_a1"), cluster.get("acc_b1"));
    }

    @Test
    void validateInvariantsDetectsAForcedConstraintViolation() {
        Instant base = Instant.parse("2026-08-08T10:00:00Z");
        LinkState state = build(List.of(
                account("acc_a", "a@example.com", "", "10.1.1.1", "", base),
                account("acc_b", "b@example.com", "", "20.1.1.1", "", base)
        ), List.of(distinct("acc_a", "acc_b")));

        state.dsu().union(state.store().indexOf("acc_a"), state.store().indexOf("acc_b"), 0.99);

        assertThatThrownBy(state::validateInvariants)
                .isInstanceOf(LinkerException.class)
                .hasMessageContaining("constraint invariant violated");
    }

    @Test
    void rejectsUnsupportedRelationAndSelfConstraint() {
        assertThatThrownBy(() -> Constraints.of(List.of(new Constraint("a", "b", "same_person"))))
                .hasMessageContaining("unsupported relation");
        assertThatThrownBy(() -> Constraints.of(List.of(distinct("a", "a"))))
                .hasMessageContaining("unsatisfiable");
    }

    @Test
    void rejectsDuplicateAccountIdsAndMissingTimestamps() {
        Instant base = Instant.parse("2026-08-08T10:00:00Z");
        assertThatThrownBy(() -> build(List.of(account("a", "x@y.com", base), account(" a ", "z@y.com", base)), List.of()))
                .hasMessageContaining("duplicate account_id");
        assertThatThrownBy(() -> build(List.of(new Account("a", "x@y.com", null, "", null, null)), List.of()))
                .hasMessageContaining("created_at");
    }
}
