package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.service.api.ApiModels.AuditEventResponse;
import io.github.rainyinsaigon.fraudlinker.service.persistence.AuditRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {
    };

    private final AuditRepository audit;
    private final JsonMapper json;

    public AuditController(AuditRepository audit, JsonMapper json) {
        this.audit = audit;
        this.json = json;
    }

    /** Most recent events first, optionally for one account or one cluster. */
    @GetMapping
    public List<AuditEventResponse> find(
            @RequestParam(name = "account_id", required = false) @Pattern(regexp = ApiModels.ACCOUNT_ID_PATTERN) String accountId,
            @RequestParam(name = "cluster_id", required = false) @Pattern(regexp = "^c[0-9]{1,12}$") String clusterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return audit.find(accountId, clusterId, limit).stream()
                .map(event -> new AuditEventResponse(event.eventId(), event.occurredAt(), event.eventType(),
                        event.accountId(), event.clusterId(), event.actor(), json.readValue(event.details(), DETAILS)))
                .toList();
    }
}
