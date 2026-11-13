package io.github.rainyinsaigon.fraudlinker.io;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonIoTest {

    @Test
    void readsBlankLinesCrlfAndMissingFinalNewline() {
        String input = "\r\n"
                + "{\"account_id\":\"a\",\"email\":\"a@example.com\",\"device_id\":null,\"ip\":\"not-an-ip\",\"payment_fingerprint\":null,\"created_at\":\"2026-01-01T00:00:00Z\"}"
                + "\r\n\r\n"
                + "{\"account_id\":\"b\",\"email\":\"b@example.com\",\"device_id\":\"d\",\"ip\":\"\",\"payment_fingerprint\":null,\"created_at\":\"2026-01-02T00:00:00+07:00\"}";

        List<Account> accounts = JsonlReader.decodeAccounts(new StringReader(input), "fixture");

        assertThat(accounts).extracting(Account::accountId).containsExactly("a", "b");
        assertThat(accounts.get(0).deviceId()).isNull();
        assertThat(accounts.get(1).deviceId()).isEqualTo("d");
        assertThat(accounts.get(1).createdAt()).isEqualTo(Instant.parse("2026-01-01T17:00:00Z"));
    }

    @Test
    void readsRecordsLongerThanSixtyFourKilobytes() {
        String longLocal = "a".repeat(70 * 1024);
        String input = "{\"account_id\":\"long\",\"email\":\"" + longLocal
                + "@example.com\",\"created_at\":\"2026-01-01T00:00:00Z\"}";

        List<Account> accounts = JsonlReader.decodeAccounts(new StringReader(input), "long-fixture");

        assertThat(accounts).singleElement().satisfies(a -> assertThat(a.email().length()).isGreaterThan(64 * 1024));
    }

    @Test
    void reportsSourceAndLineNumber() {
        String input = "\n"
                + "{\"account_id\":\"ok\",\"created_at\":\"2026-01-01T00:00:00Z\"}\n"
                + "{\"account_id\":\"\",\"created_at\":\"2026-01-01T00:00:00Z\"}\n";

        assertThatThrownBy(() -> JsonlReader.decodeAccounts(new StringReader(input), "accounts.jsonl"))
                .isInstanceOf(LinkerException.class)
                .hasMessageContaining("accounts.jsonl:3")
                .hasMessageContaining("missing account_id");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"account_id\":\"a\"}",
            "{\"account_id\":\"a\",\"created_at\":null}",
            "{\"account_id\":\"a\",\"created_at\":\"2026-01-01\"}",
            "{\"account_id\":\"a\",\"created_at\":\"2026-01-01T00:00Z\"}",
            "{\"account_id\":\"a\",\"created_at\":\"2026-02-30T00:00:00Z\"}",
            "{\"account_id\":\"a\",\"created_at\":\"2026-01-01 00:00:00Z\"}",
            "{\"account_id\":7,\"created_at\":\"2026-01-01T00:00:00Z\"}",
            "{\"account_id\":\"a\",\"email\":[],\"created_at\":\"2026-01-01T00:00:00Z\"}",
            "[1,2]",
            "{\"account_id\":\"a\",\"created_at\":\"2026-01-01T00:00:00Z\"} trailing",
            "not json",
    })
    void rejectsInvalidAccountRecords(String line) {
        assertThatThrownBy(() -> JsonlReader.decodeAccounts(new StringReader(line), "x"))
                .isInstanceOf(LinkerException.class)
                .hasMessageStartingWith("x:1: ");
    }

    @Test
    void ignoresUnknownFieldsAndMatchesNamesCaseInsensitively() {
        String line = "{\"Account_ID\":\"a\",\"EMAIL\":\"e@x.com\",\"extra\":{\"n\":1},\"created_at\":\"2026-01-01T00:00:00.123Z\"}";

        Account account = JsonlReader.decodeAccounts(new StringReader(line), "x").get(0);

        assertThat(account.accountId()).isEqualTo("a");
        assertThat(account.email()).isEqualTo("e@x.com");
        assertThat(account.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123Z"));
    }

    @Test
    void readsAndTrimsConstraints() {
        String input = "{\"account_a\":\" a \",\"account_b\":\"b\",\"relation\":\"verified_distinct\"}\n";

        assertThat(JsonlReader.decodeConstraints(new StringReader(input), "c"))
                .containsExactly(new Constraint("a", "b", "verified_distinct"));
        assertThatThrownBy(() -> JsonlReader.decodeConstraints(
                new StringReader("{\"account_a\":\"a\",\"relation\":\"verified_distinct\"}"), "c"))
                .hasMessageContaining("constraint endpoint is empty");
    }

    @Test
    void writesClustersInTheFixedLayout() {
        ClustersOutput output = new ClustersOutput(List.of(
                new Cluster("c1", List.of("acc_a", "acc_b"), 0.82),
                new Cluster("c2", List.of("acc_c"), 1.0)));

        assertThat(JsonOutput.clusters(output)).isEqualTo("""
                {
                  "clusters": [
                    {
                      "cluster_id": "c1",
                      "account_ids": [
                        "acc_a",
                        "acc_b"
                      ],
                      "confidence": 0.82
                    },
                    {
                      "cluster_id": "c2",
                      "account_ids": [
                        "acc_c"
                      ],
                      "confidence": 1
                    }
                  ]
                }
                """);
        assertThat(JsonOutput.clusters(new ClustersOutput(List.of()))).isEqualTo("{\n  \"clusters\": []\n}\n");
    }

    @Test
    void writesOneCompactAssignmentLine() {
        assertThat(JsonOutput.assignment(new Assignment("acc_new01", "c12", 0.9134)))
                .isEqualTo("{\"account_id\":\"acc_new01\",\"cluster_id\":\"c12\",\"confidence\":0.9134}");
    }

    @ParameterizedTest
    @CsvSource({"1.0, 1", "0.0, 0", "0.5, 0.5", "0.9134, 0.9134", "0.1, 0.1", "0.0001, 0.0001"})
    void formatsNumbersWithoutTrailingZerosOrExponent(double value, String expected) {
        assertThat(JsonOutput.number(value)).isEqualTo(expected);
    }

    @Test
    void escapesStringsSafely() {
        assertThat(JsonOutput.string("a\"b\\c\n<&>\u0001 "))
                .isEqualTo("\"a\\\"b\\\\c\\n\\u003c\\u0026\\u003e\\u0001\\u2028\"");
    }
}
