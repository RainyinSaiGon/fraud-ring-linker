package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.index.IndexConfig;
import io.github.rainyinsaigon.fraudlinker.index.SignalStore;
import io.github.rainyinsaigon.fraudlinker.normalize.Normalizer;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamLinker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bookkeeping the clustering result depends on: union-find sizes, confidence, and constraint counts. */
class DisjointSetAndConstraintsTest {
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void unionKeepsTheLargerComponentsRootAndAllMembers() {
        DisjointSet dsu = new DisjointSet(5);
        dsu.union(0, 1, 0.9);
        dsu.union(0, 2, 0.9);
        int root = dsu.find(0);

        int merged = dsu.union(3, 0, 0.95);

        assertThat(merged).as("the 3-member side keeps its root").isEqualTo(root);
        assertThat(dsu.members(3)).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(dsu.members(4)).containsExactly(4);
    }

    @Test
    void unionOfTheSameComponentChangesNothing() {
        DisjointSet dsu = new DisjointSet(2);
        int root = dsu.union(0, 1, 0.9);

        assertThat(dsu.union(1, 0, 0.1)).isEqualTo(root);
        assertThat(dsu.confidence(0)).as("a no-op union must not lower confidence").isEqualTo(0.9);
        assertThat(dsu.members(0)).hasSize(2);
    }

    @Test
    void confidenceIsTheWeakestLinkInTheComponent() {
        DisjointSet dsu = new DisjointSet(4);
        dsu.union(0, 1, 0.95);
        dsu.union(2, 3, 0.80);
        assertThat(dsu.confidence(4 - 4)).isEqualTo(0.95);

        dsu.union(1, 2, 0.90);

        assertThat(dsu.confidence(0)).isEqualTo(0.80);
        assertThat(dsu.confidence(3)).isEqualTo(0.80);
    }

    @Test
    void canMergeLooksAtTheSmallerSideFromEitherDirection() {
        SignalStore store = new SignalStore(IndexConfig.defaults());
        for (String id : List.of("a", "b", "c")) {
            Account account = account(id, id + "@example.com", T0);
            store.add(account, Normalizer.normalize(account));
        }
        Constraints constraints = Constraints.of(List.of(new Constraint("a", "c", Constraints.VERIFIED_DISTINCT)));
        DisjointSet dsu = new DisjointSet(3);
        dsu.union(0, 1, 0.9); // {a, b} vs {c}

        assertThat(dsu.canMerge(1, 2, store, constraints)).isFalse();
        assertThat(dsu.canMerge(2, 1, store, constraints)).isFalse();
        assertThat(dsu.canMerge(0, 1, store, constraints)).as("same component").isTrue();
    }

    @Test
    void pairCountCountsEachPairOnce() {
        Constraints constraints = Constraints.of(List.of(
                new Constraint("a", "b", Constraints.VERIFIED_DISTINCT),
                new Constraint("b", "a", Constraints.VERIFIED_DISTINCT),
                new Constraint("a", "c", Constraints.VERIFIED_DISTINCT)));

        assertThat(constraints.pairCount()).isEqualTo(2);
        assertThat(constraints.addVerifiedDistinct("c", "d")).isTrue();
        assertThat(constraints.addVerifiedDistinct("d", "c")).isFalse();
        assertThat(constraints.pairCount()).isEqualTo(3);
        assertThat(constraints.isDistinct("d", "c")).isTrue();
        assertThat(Constraints.empty().pairCount()).isZero();
    }

    @Test
    void addingAConstraintRejectsEmptyIds() {
        assertThatThrownBy(() -> Constraints.empty().addVerifiedDistinct("", "a")).hasMessageContaining("empty");
    }

    @Test
    void identitySeedFollowsTheClusterWhenAStreamedAccountJoins() {
        // Two accounts on one card form an identity seed; a third joining keeps the seed on the merged root.
        List<Account> accounts = List.of(
                account("s1", "s1@example.com", "", "10.1.0.1", "card", T0),
                account("s2", "s2@example.com", "", "10.2.0.1", "card", T0));
        LinkState state = BatchLinker.build(accounts, Constraints.empty(), ClusteringConfig.defaults());
        int seedRoot = state.dsu().find(0);
        assertThat(state.isIdentitySeedRoot(seedRoot)).isTrue();

        Assignment joined = new StreamLinker(state).assign(account("s3", "s3@example.com", "", "10.3.0.1", "card", T0));

        assertThat(joined.clusterId()).isEqualTo("c1");
        assertThat(state.isIdentitySeedRoot(state.dsu().find(2))).isTrue();
        assertThat(state.memberIds("c1")).containsExactly("s1", "s2", "s3");
        state.validateInvariants();
    }

    @Test
    void singletonsAreNotIdentitySeedsAndGetIncreasingIds() {
        LinkState state = BatchLinker.build(List.of(account("x", "x@example.com", T0)), Constraints.empty(),
                ClusteringConfig.defaults());
        StreamLinker linker = new StreamLinker(state);

        assertThat(state.isIdentitySeedRoot(state.dsu().find(0))).isFalse();
        assertThat(linker.assign(account("y", "y@example.com", T0)).clusterId()).isEqualTo("c2");
        assertThat(linker.assign(account("z", "zz.unrelated@example.org", T0)).clusterId()).isEqualTo("c3");
    }

    @Test
    void memberIdsAreSortedEvenWhenArrivalOrderIsNot() {
        List<Account> accounts = List.of(
                account("m3", "m3@example.com", "", "10.1.0.1", "card", T0),
                account("m1", "m1@example.com", "", "10.2.0.1", "card", T0),
                account("m2", "m2@example.com", "", "10.3.0.1", "card", T0));
        LinkState state = BatchLinker.build(accounts, Constraints.empty(), ClusteringConfig.defaults());

        assertThat(state.memberIds("c1")).containsExactly("m1", "m2", "m3");
    }
}
