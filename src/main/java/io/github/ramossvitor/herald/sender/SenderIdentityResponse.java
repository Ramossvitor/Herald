package io.github.ramossvitor.herald.sender;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The DNS records go out as the provider wrote them — the tenant has to copy
 * them into a zone file verbatim, so re-modelling them would only add a place
 * for them to drift.
 */
public record SenderIdentityResponse(UUID id, String kind, String identifier, String status,
		JsonNode dnsRecords, String lastError, Instant verifiedAt) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	static SenderIdentityResponse from(SenderIdentity identity) {
		return new SenderIdentityResponse(identity.getId(), identity.getKind().name(),
				identity.getIdentifier(), identity.getStatus().name(),
				identity.getDnsRecords() == null ? null : JSON.readTree(identity.getDnsRecords()),
				identity.getLastError(), identity.getVerifiedAt());
	}
}
