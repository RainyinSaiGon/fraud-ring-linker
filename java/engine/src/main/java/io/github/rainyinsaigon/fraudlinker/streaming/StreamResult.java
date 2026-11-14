package io.github.rainyinsaigon.fraudlinker.streaming;

import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.domain.MergeCandidate;

import java.util.List;

/**
 * The assignment for one streaming account, plus the other clusters it would
 * also have joined. A candidate is listed only if merging it with the assigned
 * cluster would keep every verified-distinct pair apart.
 */
public record StreamResult(Assignment assignment, List<MergeCandidate> mergeCandidates) {
    public StreamResult {
        mergeCandidates = List.copyOf(mergeCandidates);
    }
}
