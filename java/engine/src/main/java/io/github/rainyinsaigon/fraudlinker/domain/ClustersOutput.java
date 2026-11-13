package io.github.rainyinsaigon.fraudlinker.domain;

import java.util.List;

/** The batch output envelope: {"clusters": [...]}. */
public record ClustersOutput(List<Cluster> clusters) {
    public ClustersOutput {
        clusters = List.copyOf(clusters);
    }
}
