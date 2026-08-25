package io.github.ramossvitor.herald.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Webhooks arrive out of order and more than once, so this precedence is what
 * keeps the recorded state from oscillating with the delivery order.
 */
class DeliveryStateTest {

	@Test
	void anythingBeatsNothing() {
		for (DeliveryState state : DeliveryState.values()) {
			assertThat(DeliveryState.supersedes(null, state)).as(state.name()).isTrue();
		}
	}

	@Test
	void aLateDeliveredDoesNotUndoABounce() {
		// The case that matters: the address is on the suppression list because
		// of that bounce, and a state saying "delivered" would contradict it.
		assertThat(DeliveryState.supersedes(DeliveryState.BOUNCED, DeliveryState.DELIVERED)).isFalse();
		assertThat(DeliveryState.supersedes(DeliveryState.COMPLAINED, DeliveryState.DELIVERED)).isFalse();
		assertThat(DeliveryState.supersedes(DeliveryState.FAILED_AT_PROVIDER, DeliveryState.DELIVERED)).isFalse();
	}

	@Test
	void terminalStatesAreNotReplacedByOtherTerminalStatesEither() {
		assertThat(DeliveryState.supersedes(DeliveryState.BOUNCED, DeliveryState.COMPLAINED)).isFalse();
		assertThat(DeliveryState.supersedes(DeliveryState.COMPLAINED, DeliveryState.BOUNCED)).isFalse();
	}

	@Test
	void aDelayIsAlwaysProvisional() {
		assertThat(DeliveryState.supersedes(DeliveryState.DELAYED, DeliveryState.DELIVERED)).isTrue();
		assertThat(DeliveryState.supersedes(DeliveryState.DELAYED, DeliveryState.BOUNCED)).isTrue();
	}

	@Test
	void deliveredStillYieldsToBadNews() {
		// Delivered to one server, bounced by the next hop — both are true, and
		// the bounce is the one that decides whether to mail the address again.
		assertThat(DeliveryState.supersedes(DeliveryState.DELIVERED, DeliveryState.BOUNCED)).isTrue();
		assertThat(DeliveryState.supersedes(DeliveryState.DELIVERED, DeliveryState.COMPLAINED)).isTrue();
		assertThat(DeliveryState.supersedes(DeliveryState.DELIVERED, DeliveryState.FAILED_AT_PROVIDER)).isTrue();
	}

	@Test
	void deliveredDoesNotWalkBackToStillTrying() {
		// The delay preceded the delivery and merely arrived after it. Applying
		// it would report a message that landed as one still in flight.
		assertThat(DeliveryState.supersedes(DeliveryState.DELIVERED, DeliveryState.DELAYED)).isFalse();
	}

	@Test
	void redeliveringTheSameEventChangesNothing() {
		for (DeliveryState state : DeliveryState.values()) {
			assertThat(DeliveryState.supersedes(state, state)).as(state.name()).isFalse();
		}
	}

	@Test
	void onlyProvisionalStatesAreNonTerminal() {
		assertThat(DeliveryState.DELIVERED.isTerminal()).isFalse();
		assertThat(DeliveryState.DELAYED.isTerminal()).isFalse();
		assertThat(DeliveryState.BOUNCED.isTerminal()).isTrue();
		assertThat(DeliveryState.COMPLAINED.isTerminal()).isTrue();
		assertThat(DeliveryState.FAILED_AT_PROVIDER.isTerminal()).isTrue();
	}
}
