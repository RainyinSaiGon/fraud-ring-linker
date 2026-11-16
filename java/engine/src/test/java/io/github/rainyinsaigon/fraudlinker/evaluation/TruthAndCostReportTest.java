package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TruthAndCostReportTest {
    private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");

    @TempDir
    Path dir;

    private Path write(String json) throws IOException {
        Path file = dir.resolve("truth.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    void readsTheVersionedFormatWithKinds() throws IOException {
        Truth truth = Truth.read(write("""
                {"schema_version": 1, "seed": 1, "run_id": "s1",
                 "actors": [
                   {"actor_id": "ring_0", "kind": "fraud_ring", "account_ids": ["a", "b"]},
                   {"actor_id": "person_c", "kind": "office_worker", "group": "office", "account_ids": ["c"]}],
                 "account_to_actor": {"a": "ring_0", "b": "ring_0", "c": "person_c"},
                 "fraud_actor_ids": ["ring_0"]}
                """));

        assertThat(truth.accountToActor()).hasSize(3);
        assertThat(truth.fraudActorIds()).containsExactly("ring_0");
        assertThat(truth.kindOfAccount("c")).isEqualTo("office_worker");
        assertThat(truth.kindOfAccount("missing")).isEqualTo(Truth.UNKNOWN_KIND);
    }

    @Test
    void readsVersionTwoWithMuleFieldsTheSameWay() throws IOException {
        // Version 2 (simulator transfers) adds mule fields; linking evaluation reads what it did before.
        Truth truth = Truth.read(write("""
                {"schema_version": 2, "seed": 1, "run_id": "s1",
                 "actors": [
                   {"actor_id": "ring_0", "kind": "fraud_ring", "account_ids": ["a", "b"]},
                   {"actor_id": "person_c", "kind": "customer", "account_ids": ["c"]}],
                 "account_to_actor": {"a": "ring_0", "b": "ring_0", "c": "person_c"},
                 "fraud_actor_ids": ["ring_0"],
                 "mule_chains": [{"chain_id": "mule_chain_0", "account_ids": ["c"], "victims": 5,
                                  "cash_out": "ext_cashout_s1_0"}],
                 "mule_account_ids": ["c"],
                 "transfer_cases": [{"case": "small_business", "account_id": "a"}]}
                """));

        assertThat(truth.accountToActor()).hasSize(3);
        assertThat(truth.fraudActorIds()).containsExactly("ring_0");
        assertThat(truth.kindOfAccount("c")).isEqualTo("customer");
    }

    @Test
    void readsVersionThreeWithTakeoverFieldsTheSameWay() throws IOException {
        // Version 3 (simulator logins) adds takeover fields; linking evaluation reads what it did before.
        Truth truth = Truth.read(write("""
                {"schema_version": 3, "seed": 1, "run_id": "s1",
                 "actors": [
                   {"actor_id": "ring_0", "kind": "fraud_ring", "account_ids": ["a", "b"]},
                   {"actor_id": "person_c", "kind": "customer", "account_ids": ["c"]}],
                 "account_to_actor": {"a": "ring_0", "b": "ring_0", "c": "person_c"},
                 "fraud_actor_ids": ["ring_0"],
                 "mule_chains": [], "mule_account_ids": [], "transfer_cases": [],
                 "takeovers": [{"account_id": "c", "kind": "phishing", "attacker_device": "dev_x",
                                "first_bad_login_id": "s1_l0000009", "first_bad_at": "2026-11-14T13:35:00Z",
                                "attacker_login_ids": ["s1_l0000009"]}],
                 "takeover_account_ids": ["c"],
                 "login_cases": [{"case": "holiday", "account_id": "a"}]}
                """));

        assertThat(truth.accountToActor()).hasSize(3);
        assertThat(truth.fraudActorIds()).containsExactly("ring_0");
        assertThat(truth.kindOfAccount("c")).isEqualTo("customer");
    }

    @Test
    void readsTheOriginalFormatWithoutKinds() throws IOException {
        Truth truth = Truth.read(write("""
                {"account_to_actor": {"a": "x", "b": "x"}, "fraud_actor_ids": ["x"]}
                """));

        assertThat(truth.actorKinds()).isEmpty();
        assertThat(truth.kindOfAccount("a")).isEqualTo(Truth.UNKNOWN_KIND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"fraud_actor_ids\": []}",
            "{\"account_to_actor\": {}}",
            "{\"account_to_actor\": {\"a\": \"\"}}",
            "{\"account_to_actor\": {\" \": \"x\"}}",
            "{\"account_to_actor\": {\"a\": \"x\"}, \"fraud_actor_ids\": [\"nobody\"]}",
            "{\"account_to_actor\": {\"a\": \"x\"}, \"fraud_actor_ids\": [\"x\", \"x\"]}",
            "{\"account_to_actor\": {\"a\": \"x\"}, \"fraud_actor_ids\": [\"\"]}",
            "not json",
    })
    void rejectsInvalidTruth(String json) throws IOException {
        Path file = write(json);
        assertThatThrownBy(() -> Truth.read(file)).isInstanceOf(LinkerException.class);
    }

    @Test
    void streamingSplitsARingThatBatchLinkingCatches() {
        // r1 shares a phone and a near-identical email with r3; r2 shares only a card with r3.
        // In stream order r1, r2, r3, the first two start separate clusters and r3 can join
        // only one of them, because streaming never merges existing clusters.
        List<Account> accounts = List.of(
                account("r1", "maria.lopez@x.com", "phone-1", "10.1.0.1", "", T0),
                account("r2", "zz.other@y.com", "", "10.2.0.1", "card-1", T0.plusSeconds(60)),
                account("r3", "maria.lopes@x.com", "phone-1", "10.3.0.1", "card-1", T0.plusSeconds(120)),
                account("alice", "alice@z.com", "", "10.4.0.1", "", T0));
        Truth truth = new Truth(Map.of("r1", "ring_0", "r2", "ring_0", "r3", "ring_0", "alice", "p_alice"),
                java.util.Set.of("ring_0"), Map.of("ring_0", "fraud_ring", "p_alice", "customer"));

        CostReport.Result result = CostReport.run(accounts, List.of(), truth, ClusteringConfig.defaults(), CostModel.DEFAULT);

        assertThat(result.batch().fullyRecoveredFraudRings()).isEqualTo(1);
        assertThat(result.batch().cost()).isZero();
        assertThat(result.stream().splitFraudRings()).isEqualTo(1);
        assertThat(result.stream().cost()).isEqualTo(2000);
        assertThat(result.stream().wronglyLinkedAccounts()).isZero();
        assertThat(result.batchClusters().clusters()).hasSize(2);
        assertThat(result.streamClusters().clusters()).hasSize(3);

        // r3 proposes merging the cluster it did not join; accepting it recovers the ring.
        assertThat(result.proposals()).isEqualTo(1);
        assertThat(result.proposalsMerged()).isEqualTo(1);
        assertThat(result.streamWithProposals().fullyRecoveredFraudRings()).isEqualTo(1);
        assertThat(result.streamWithProposals().cost()).isZero();
        assertThat(result.streamWithProposalsClusters().clusters()).hasSize(2);
    }

    @Test
    void aProposalThatWouldJoinVerifiedDistinctAccountsIsNotMerged() {
        // r4 links both r1 (phone) and r2 (card), but a constraint says r1 and r2 are different people,
        // so r4 joins one of them and no proposal is opened at all.
        List<Account> accounts = List.of(
                account("r1", "maria.lopez@x.com", "phone-1", "10.1.0.1", "", T0),
                account("r2", "zz.other@y.com", "", "10.2.0.1", "card-1", T0.plusSeconds(60)),
                account("r4", "maria.lopes@x.com", "phone-1", "10.3.0.1", "card-1", T0.plusSeconds(120)));
        Truth truth = new Truth(Map.of("r1", "p1", "r2", "p2", "r4", "p1"), java.util.Set.of(),
                Map.of("p1", "customer", "p2", "customer"));
        List<Constraint> constraints = List.of(new Constraint("r1", "r2", "verified_distinct"));

        CostReport.Result result = CostReport.run(accounts, constraints, truth, ClusteringConfig.defaults(),
                CostModel.DEFAULT);

        assertThat(result.proposals()).isZero();
        assertThat(result.streamWithProposalsClusters()).isEqualTo(result.streamClusters());
    }
}
