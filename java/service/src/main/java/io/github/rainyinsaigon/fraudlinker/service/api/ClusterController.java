package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.clustering.ClusterExplainer;
import io.github.rainyinsaigon.fraudlinker.service.LinkerProperties;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.ClusterResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.DecisionRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.DecisionResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.DecisionResultResponse;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.LinkResponse;
import io.github.rainyinsaigon.fraudlinker.service.app.ClusterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/clusters")
public class ClusterController {
    private static final String CLUSTER_ID_PATTERN = "^c[0-9]{1,12}$";

    private final ClusterService clusters;
    private final LinkerProperties properties;

    public ClusterController(ClusterService clusters, LinkerProperties properties) {
        this.clusters = clusters;
        this.properties = properties;
    }

    /** The review queue: linked clusters that still need an analyst's decision. */
    @GetMapping
    public List<ApiModels.ClusterSummaryResponse> reviewQueue(
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return clusters.reviewQueue(limit).stream()
                .map(item -> new ApiModels.ClusterSummaryResponse(item.clusterId(), item.size(), item.confidence(),
                        item.risk().action(), item.risk().reason()))
                .toList();
    }

    /** Members, recommended action, and which evidence links them. */
    @GetMapping("/{clusterId}")
    public ClusterResponse explain(@PathVariable @Pattern(regexp = CLUSTER_ID_PATTERN) String clusterId) {
        ClusterService.ClusterView view = clusters.explain(clusterId);
        ClusterExplainer.Explanation explanation = view.explanation();
        List<String> members = explanation.accountIds();
        int limit = properties.maxClusterMembersReturned();
        List<LinkResponse> links = explanation.links().stream()
                .map(link -> new LinkResponse(link.accountA(), link.accountB(), link.rule(), link.score(),
                        link.sharedSignals()))
                .toList();
        return new ClusterResponse(
                explanation.clusterId(),
                members.size(),
                members.size() > limit ? members.subList(0, limit) : members,
                members.size() > limit,
                explanation.confidence(),
                view.risk().action(),
                view.risk().reason(),
                links,
                explanation.truncated(),
                view.latestDecision().map(DecisionResponse::of).orElse(null));
    }

    @GetMapping("/{clusterId}/decisions")
    public List<DecisionResponse> history(
            @PathVariable @Pattern(regexp = CLUSTER_ID_PATTERN) String clusterId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return clusters.history(clusterId, limit).stream().map(DecisionResponse::of).toList();
    }

    /** Records an analyst's decision for every account currently in the cluster. */
    @PostMapping("/{clusterId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionResultResponse decide(@PathVariable @Pattern(regexp = CLUSTER_ID_PATTERN) String clusterId,
                                         @Valid @RequestBody DecisionRequest request) {
        ClusterService.DecisionResult result = clusters.decide(clusterId, request.outcome(), request.analyst().strip(),
                request.note());
        return new DecisionResultResponse(DecisionResponse.of(result.decision()), result.risk().action(),
                result.risk().reason());
    }
}
