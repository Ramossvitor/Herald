package io.github.ramossvitor.herald.tenant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

	/**
	 * Authentication lookup: the tenant comes along in one query because every
	 * hit needs its slug and status.
	 */
	@Query("select k from ApiKey k join fetch k.tenant where k.keyHash = :keyHash and k.revokedAt is null")
	Optional<ApiKey> findActiveByKeyHash(String keyHash);

	/**
	 * Kept out of the entity's dirty-checking path: last-used is telemetry, and
	 * the caller throttles how often it is written.
	 */
	@Modifying
	@Transactional
	@Query("update ApiKey k set k.lastUsedAt = :when where k.id = :id")
	void markUsed(UUID id, Instant when);
}
