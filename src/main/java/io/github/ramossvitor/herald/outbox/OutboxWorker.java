package io.github.ramossvitor.herald.outbox;

import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.ramossvitor.herald.common.HeraldProperties;
import io.github.ramossvitor.herald.sender.Channel;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Polls the outbox and pushes due messages through their channel's provider,
 * one at a time, paced under the provider's requests-per-second limit.
 *
 * Delivery is at-least-once from the outbox's point of view; the provider
 * idempotency key collapses that to effectively-once at the destination.
 */
@Component
@Lazy(false) // the app runs with global lazy-init, which would otherwise never register @Scheduled beans
public class OutboxWorker {

	private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

	/** Ticks without work before the worker considers itself idle. */
	private static final int IDLE_AFTER_TICKS = 3;
	/** While idle, only every Nth tick touches the database (serverless
	 * Postgres bills compute time; a hot poll would keep it awake for nothing). */
	private static final int IDLE_POLL_EVERY_TICKS = 10;
	/** Batches one channel may take before the pass moves on. Without a ceiling
	 * a deep backlog would hold the thread for backlog × send-interval, and
	 * every other channel's due messages would wait out the whole of it. The
	 * remainder stays due and the next tick continues it. */
	private static final int MAX_BATCHES_PER_CHANNEL = 5;

	private final OutboxStore store;
	private final Map<Channel, ChannelProvider> providers = new EnumMap<>(Channel.class);
	private final HeraldProperties.Outbox properties;
	private final RetryPolicy retryPolicy;
	private final MeterRegistry metrics;

	private final AtomicBoolean nudged = new AtomicBoolean(false);
	private final Set<Channel> warnedNotConfigured = EnumSet.noneOf(Channel.class);
	private int idleTicks = 0;
	private int ticksSinceLastPoll = 0;

	public OutboxWorker(OutboxStore store, List<ChannelProvider> providers, HeraldProperties properties,
			MeterRegistry metrics) {
		this.store = store;
		for (ChannelProvider provider : providers) {
			ChannelProvider clash = this.providers.put(provider.channel(), provider);
			if (clash != null) {
				throw new IllegalStateException("two providers claim channel " + provider.channel());
			}
		}
		this.properties = properties.outbox();
		this.retryPolicy = new RetryPolicy(this.properties.maxAttempts());
		this.metrics = metrics;
	}

	/** Called after a submission commits so the next tick polls immediately. */
	public void nudge() {
		nudged.set(true);
	}

	@Scheduled(fixedDelayString = "${herald.outbox.poll-interval}")
	void tick() {
		ticksSinceLastPoll++;
		boolean idle = idleTicks >= IDLE_AFTER_TICKS;
		if (idle && !nudged.get() && ticksSinceLastPoll < IDLE_POLL_EVERY_TICKS) {
			return;
		}
		nudged.set(false);
		ticksSinceLastPoll = 0;
		int processed = runOnce();
		idleTicks = processed > 0 ? 0 : idleTicks + 1;
	}

	/**
	 * One pass over what is currently due, on every channel. A channel that
	 * throws costs its own pass and nothing else — the point of the whole
	 * arrangement is that no channel can take another down.
	 *
	 * Public for tests.
	 */
	public int runOnce() {
		int processed = 0;
		for (ChannelProvider provider : providers.values()) {
			try {
				processed += drain(provider);
			}
			catch (RuntimeException ex) {
				// Rows already claimed stay SENDING; OutboxRecovery releases them.
				log.error("{} pass aborted", provider.channel(), ex);
			}
		}
		return processed;
	}

	/** Everything currently due on one channel. Public for tests. */
	public int runOnce(Channel channel) {
		ChannelProvider provider = providers.get(channel);
		return provider == null ? 0 : drain(provider);
	}

	private int drain(ChannelProvider provider) {
		if (!provider.configured()) {
			if (warnedNotConfigured.add(provider.channel())) {
				log.warn("{} has no provider credentials — dispatch is paused, messages will queue as PENDING",
						provider.channel());
			}
			return 0;
		}
		int processed = 0;
		for (int batches = 0; batches < MAX_BATCHES_PER_CHANNEL; batches++) {
			List<Message> batch = store.claimDueBatch(provider.channel(), properties.batchSize());
			if (batch.isEmpty()) {
				return processed;
			}
			for (int i = 0; i < batch.size(); i++) {
				if (i > 0) {
					pace(provider);
				}
				processOne(provider, batch.get(i));
				processed++;
			}
			if (batch.size() < properties.batchSize()) {
				return processed;
			}
			pace(provider);
		}
		log.info("{} still has messages due after {} batches — resuming next tick", provider.channel(),
				MAX_BATCHES_PER_CHANNEL);
		return processed;
	}

	private void processOne(ChannelProvider provider, Message message) {
		MDC.put("messageId", message.getId().toString());
		MDC.put("channel", message.getChannel().name());
		try {
			ChannelProvider.Attempt attempt = provider.send(message);

			int attemptNumber = message.getAttemptCount() + 1;
			RetryPolicy.Decision decision = retryPolicy.decide(attempt.classification(), attemptNumber,
					attempt.retryAfterSeconds());
			store.recordOutcome(message.getId(), decision, attempt.providerMessageId(), attempt.error());

			String channel = message.getChannel().name().toLowerCase();
			switch (decision.status()) {
				case SENT -> metrics.counter("herald.messages.sent", "channel", channel).increment();
				case FAILED -> {
					metrics.counter("herald.messages.failed", "channel", channel).increment();
					log.error("message failed after attempt {}: {}", attemptNumber, attempt.classification());
				}
				case PENDING -> log.warn("attempt {} got {}, retrying in {}", attemptNumber, attempt.classification(),
						decision.delay());
				case SENDING -> throw new IllegalStateException("unreachable");
			}
		}
		finally {
			MDC.remove("messageId");
			MDC.remove("channel");
		}
	}

	private void pace(ChannelProvider provider) {
		Duration interval = provider.sendInterval();
		try {
			Thread.sleep((interval != null ? interval : properties.sendInterval()).toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
