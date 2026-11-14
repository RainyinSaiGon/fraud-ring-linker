package io.github.rainyinsaigon.fraudlinker.domain;

import java.util.List;

/** One batch output cluster. Account IDs are sorted. */
public record Cluster(String clusterId, List<String> accountIds, double confidence) {
    public Cluster {
        accountIds = List.copyOf(accountIds);
    }
}
