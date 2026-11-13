package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState.ConstraintOutcome;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.scoring.Scorer;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamLinker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkStateTest {
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    /** acc_a + acc_b share a rare payment card; acc_c stands alone. */
    private static LinkState pairAndSingleton() {
        List<Account> accounts = List.of(
                account("acc_a", "alice@example.com", "", "10.0.0.1", "pay-ab", T0),
                account("acc_b", "bob.smith@example.com", "", "20.0.0.1", "pay-ab", T0),
                account("acc_c", "carol@example.com", "", "30.0.0.1", "", T0));
        return BatchLinker.build(accounts, Constraints.empty(), ClusteringConfig.defaults());
    }

    @Test
    void looksUpClustersByAccountAndById() {
        LinkState state = pairAndSingleton();

        assertThat(state.clusterIdOf("acc_a")).contains("c1");
        assertThat(state.clusterIdOf("acc_b")).contains("c1");
        assertThat(state.clusterIdOf("acc_c")).contains("c2");
        assertThat(state.clusterIdOf("nobody")).isEmpty();
        assertThat(state.memberIds("c1")).containsExactly("acc_a", "acc_b");
        assertThat(state.memberIds("c99")).isEmpty();
        assertThat(state.confidence("c2")).isEqualTo(1.0);
        assertThatThrownBy(() -> state.confidence("c99")).isInstanceOf(LinkerException.class);
    }

    @Test
    void lookupsFollowStreamedAccounts() {
        LinkState state = pairAndSingleton();
        StreamLinker linker = new StreamLinker(state);

        Assignment joined = linker.assign(account("acc_d", "d@example.com", "", "40.0.0.1", "pay-ab", T0));
        Assignment alone = linker.assign(account("acc_e", "e@example.com", "", "50.0.0.1", "", T0));

        assertThat(joined.clusterId()).isEqualTo("c1");
        assertThat(state.memberIds("c1")).containsExactly("acc_a", "acc_b", "acc_d");
        assertThat(alone.clusterId()).isEqualTo("c3");
        assertThat(state.memberIds("c3")).containsExactly("acc_e");
        state.validateInvariants();
    }

    @Test
    void runtimeConstraintBlocksFutureLinks() {
        LinkState state = pairAndSingleton();

        assertThat(state.addConstraint("acc_a", "acc_new")).isEqualTo(ConstraintOutcome.ADDED);
        assertThat(state.addConstraint("acc_new", "acc_a")).isEqualTo(ConstraintOutcome.ALREADY_PRESENT);

        Assignment assignment = new StreamLinker(state)
                .assign(account("acc_new", "n@example.com", "", "60.0.0.1", "pay-ab", T0));

        assertThat(assignment.clusterId()).as("must not join acc_a's cluster").isNotEqualTo("c1");
        state.validateInvariants();
    }

    @Test
    void runtimeConstraintInsideAnExistingClusterIsRefusedAndNotStored() {
        LinkState state = pairAndSingleton();

        assertThat(state.addConstraint("acc_a", "acc_b")).isEqualTo(ConstraintOutcome.CONFLICTS_WITH_EXISTING_CLUSTER);
        assertThat(state.constraints().isDistinct("acc_a", "acc_b")).isFalse();
        state.validateInvariants();
    }

    @Test
    void runtimeConstraintRejectsSelfPairs() {
        LinkState state = pairAndSingleton();
        assertThatThrownBy(() -> state.addConstraint("acc_c", "acc_c")).hasMessageContaining("unsatisfiable");
    }

    @Test
    void explainsWhichRuleAndSignalsLinkAPair() {
        LinkState state = pairAndSingleton();

        ClusterExplainer.Explanation explanation = ClusterExplainer.explain(state, "c1", 50).orElseThrow();

        assertThat(explanation.accountIds()).containsExactly("acc_a", "acc_b");
        assertThat(explanation.truncated()).isFalse();
        assertThat(explanation.links()).singleElement().satisfies(link -> {
            assertThat(link.accountA()).isEqualTo("acc_a");
            assertThat(link.accountB()).isEqualTo("acc_b");
            assertThat(link.rule()).isEqualTo(Scorer.RULE_STRONG_PAYMENT);
            assertThat(link.sharedSignals()).containsExactly("same_payment_method");
            assertThat(link.score()).isBetween(0.7, 1.0);
        });
        assertThat(ClusterExplainer.explain(state, "c99", 50)).isEmpty();
    }

    @Test
    void explanationOfSingletonHasNoLinks() {
        assertThat(ClusterExplainer.explain(pairAndSingleton(), "c2", 50).orElseThrow().links()).isEmpty();
    }

    @Test
    void explanationIsTruncatedForLargeClusters() {
        List<Account> ring = List.of(
                account("r1", "r1@x.com", "", "", "card", T0),
                account("r2", "r2@x.com", "", "", "card", T0),
                account("r3", "r3@x.com", "", "", "card", T0),
                account("r4", "r4@x.com", "", "", "card", T0));
        LinkState state = BatchLinker.build(ring, Constraints.empty(), ClusteringConfig.defaults());

        ClusterExplainer.Explanation explanation = ClusterExplainer.explain(state, "c1", 2).orElseThrow();

        assertThat(explanation.truncated()).isTrue();
        assertThat(explanation.accountIds()).hasSize(4);
        assertThat(explanation.links()).hasSize(1);
    }
}
