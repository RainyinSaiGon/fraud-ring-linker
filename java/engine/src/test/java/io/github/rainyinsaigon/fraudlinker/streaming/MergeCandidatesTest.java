package io.github.rainyinsaigon.fraudlinker.streaming;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState.MergeOutcome;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.domain.MergeCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static io.github.rainyinsaigon.fraudlinker.Fixtures.assertValidCompletePartition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * r1 and r3 share a phone and a near-identical email; r2 and r3 share a card.
 * Streamed in the order r1, r2, r3, the ring is split by arrival order: r1 and r2
 * start separate clusters, and r3 can join only one of them.
 */
class MergeCandidatesTest {
    private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
    private static final Account R1 = account("r1", "maria.lopez@x.com", "phone-1", "10.1.0.1", "", T0);
    private static final Account R2 = account("r2", "zz.other@y.com", "", "10.2.0.1", "card-1", T0.plusSeconds(60));
    private static final Account R3 = account("r3", "maria.lopes@x.com", "phone-1", "10.3.0.1", "card-1", T0.plusSeconds(120));

    private static LinkState empty(Constraint... constraints) {
        return BatchLinker.build(List.of(), Constraints.of(List.of(constraints)), ClusteringConfig.defaults());
    }

    private static Constraint distinct(String a, String b) {
        return new Constraint(a, b, Constraints.VERIFIED_DISTINCT);
    }

    /** Streams r1 and r2 (c1 and c2), then returns r3's result. */
    private static StreamResult streamRing(LinkState state) {
        StreamLinker linker = new StreamLinker(state);
        assertThat(linker.assignWithCandidates(R1).mergeCandidates()).isEmpty();
        assertThat(linker.assignWithCandidates(R2).mergeCandidates()).isEmpty();
        return linker.assignWithCandidates(R3);
    }

    @Test
    void anArrivalOrderSplitReportsTheOtherClusterAsACandidate() {
        LinkState state = empty();

        StreamResult result = streamRing(state);

        String joined = result.assignment().clusterId();
        String other = joined.equals("c1") ? "c2" : "c1";
        assertThat(result.mergeCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.clusterId()).isEqualTo(other);
            assertThat(candidate.score()).isBetween(ClusteringConfig.defaults().streamThreshold(), 1.0);
            assertThat(candidate.score()).isLessThanOrEqualTo(result.assignment().confidence());
            assertThat(candidate.rule()).isNotEmpty();
        });
        // Nothing is merged until someone accepts the candidate.
        assertThat(state.clusters().clusters()).hasSize(2);
        assertValidCompletePartition(state);
    }

    @Test
    void assignReturnsTheSameAssignmentWithoutCandidates() {
        StreamResult withCandidates = streamRing(empty());

        StreamLinker plain = new StreamLinker(empty());
        plain.assign(R1);
        plain.assign(R2);

        assertThat(plain.assign(R3)).isEqualTo(withCandidates.assignment());
    }

    @Test
    void aSingleLinkedClusterHasNoCandidates() {
        StreamLinker linker = new StreamLinker(empty());
        linker.assign(R1);

        StreamResult result = linker.assignWithCandidates(R3);

        assertThat(result.assignment().clusterId()).isEqualTo("c1");
        assertThat(result.mergeCandidates()).isEmpty();
    }

    @Test
    void aCandidateIsNeverReportedWhenTheMergeWouldBreakAConstraint() {
        // r1 and r2 are verified distinct, so their clusters may never become one.
        StreamResult result = streamRing(empty(distinct("r1", "r2")));

        assertThat(result.mergeCandidates()).isEmpty();
    }

    @Test
    void aClusterTheNewAccountMayNotJoinIsNeverACandidate() {
        LinkState state = empty(distinct("r3", "r2"));

        StreamResult result = streamRing(state);

        assertThat(result.assignment().clusterId()).isEqualTo("c1");
        assertThat(result.mergeCandidates()).isEmpty();
    }

    @Test
    void anAccountThatConflictsWithEveryOptionStartsAloneWithNoCandidates() {
        LinkState state = empty(distinct("r3", "r1"), distinct("r3", "r2"));

        StreamResult result = streamRing(state);

        assertThat(result.assignment().clusterId()).isEqualTo("c3");
        assertThat(result.assignment().confidence()).isEqualTo(1.0);
        assertThat(result.mergeCandidates()).isEmpty();
    }

    @Test
    void acceptingACandidateMergesUnderTheOlderClusterId() {
        LinkState state = empty();
        StreamResult result = streamRing(state);
        MergeCandidate candidate = result.mergeCandidates().get(0);
        boolean anySeed = isSeed(state, "r1") || isSeed(state, "r2");
        assertThat(state.clusterCount()).isEqualTo(2);

        MergeOutcome outcome = state.mergeClusters(result.assignment().clusterId(), candidate.clusterId(), candidate.score());

        assertThat(outcome).isEqualTo(MergeOutcome.MERGED);
        assertThat(state.clusterCount()).isEqualTo(1);
        assertThat(state.memberIds("c1")).containsExactly("r1", "r2", "r3");
        assertThat(state.memberIds("c2")).isEmpty();
        assertThat(state.clusterIdOf("r2")).contains("c1");
        assertThat(state.confidence("c1")).isLessThanOrEqualTo(candidate.score());
        assertThat(isSeed(state, "r1")).isEqualTo(anySeed);
        assertThatThrownBy(() -> state.confidence("c2")).isInstanceOf(LinkerException.class);
        assertValidCompletePartition(state);

        // Streaming carries on: the next new cluster is still c3, and the merged cluster can still grow.
        StreamLinker linker = new StreamLinker(state);
        assertThat(linker.assign(account("loner", "someone@else.org", "", "10.9.0.1", "", T0.plusSeconds(300))).clusterId())
                .isEqualTo("c3");
        assertThat(linker.assign(account("r4", "maria.lopez+2@x.com", "phone-1", "10.4.0.1", "card-1", T0.plusSeconds(400)))
                .clusterId()).isEqualTo("c1");
        assertValidCompletePartition(state);
    }

    @Test
    void theOlderIdSurvivesWhicheverOrderTheClustersAreGiven() {
        LinkState state = empty();
        StreamLinker linker = new StreamLinker(state);
        linker.assign(R1);
        linker.assign(R2);

        assertThat(state.mergeClusters("c2", "c1", 0.9)).isEqualTo(MergeOutcome.MERGED);

        assertThat(state.memberIds("c1")).containsExactly("r1", "r2");
        assertThat(state.confidence("c1")).isEqualTo(0.9);
        assertValidCompletePartition(state);
    }

    @Test
    void mergeRefusesAConstraintConflictAndChangesNothing() {
        LinkState state = empty(distinct("r1", "r2"));
        StreamLinker linker = new StreamLinker(state);
        linker.assign(R1);
        linker.assign(R2);

        assertThat(state.mergeClusters("c1", "c2", 0.9)).isEqualTo(MergeOutcome.CONFLICTS_WITH_CONSTRAINT);

        assertThat(state.memberIds("c1")).containsExactly("r1");
        assertThat(state.memberIds("c2")).containsExactly("r2");
        assertValidCompletePartition(state);
    }

    @Test
    void mergingAClusterWithItselfOrAnUnknownClusterIsHandled() {
        LinkState state = empty();
        new StreamLinker(state).assign(R1);

        assertThat(state.mergeClusters("c1", "c1", 0.9)).isEqualTo(MergeOutcome.ALREADY_ONE_CLUSTER);
        assertThat(state.confidence("c1")).isEqualTo(1.0);
        assertThatThrownBy(() -> state.mergeClusters("c1", "c9", 0.9)).hasMessageContaining("unknown cluster \"c9\"");
        assertThatThrownBy(() -> state.mergeClusters("c8", "c1", 0.9)).hasMessageContaining("unknown cluster \"c8\"");
    }

    private static boolean isSeed(LinkState state, String accountId) {
        return state.isIdentitySeedRoot(state.dsu().find(state.store().indexOf(accountId)));
    }
}
