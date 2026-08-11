package io.github.ramossvitor.herald.email.outbox;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns SENDING rows abandoned by a crashed worker to the queue. Ten
 * minutes is far beyond any legitimate pass, and the provider idempotency key
 * makes the re-send safe.
 */
@Component
@Lazy(false) // see OutboxWorker
public class OutboxRecovery {

	private static final Logger log = LoggerFactory.getLogger(OutboxRecovery.class);
	private static final Duration STUCK_AFTER = Duration.ofMinutes(10);

	private final OutboxStore store;

	public OutboxRecovery(OutboxStore store) {
		this.store = store;
	}

	@Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
	void run() {
		int released = store.releaseStuckSending(STUCK_AFTER);
		if (released > 0) {
			log.warn("released {} messages stuck in SENDING back to PENDING", released);
		}
	}
}
