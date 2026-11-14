package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState.ConstraintOutcome;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.ConstraintRequest;
import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.ConstraintResponse;
import io.github.rainyinsaigon.fraudlinker.service.app.ConstraintService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/constraints")
public class ConstraintController {
    private final ConstraintService constraints;

    public ConstraintController(ConstraintService constraints) {
        this.constraints = constraints;
    }

    /**
     * Records that two accounts are different people. 201 when new, 200 when it
     * was already known, 409 when the two are already linked in one cluster.
     */
    @PostMapping
    public ResponseEntity<ConstraintResponse> add(@Valid @RequestBody ConstraintRequest request) {
        ConstraintOutcome outcome = constraints.addVerifiedDistinct(request.accountA(), request.accountB(),
                request.actor().strip());
        boolean added = outcome == ConstraintOutcome.ADDED;
        ConstraintResponse body = new ConstraintResponse(request.accountA(), request.accountB(),
                Constraints.VERIFIED_DISTINCT, added ? "added" : "already_present");
        return ResponseEntity.status(added ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }
}
