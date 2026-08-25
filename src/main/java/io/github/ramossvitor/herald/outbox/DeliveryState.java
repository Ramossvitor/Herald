package io.github.ramossvitor.herald.outbox;

/**
 * What the provider reported about a message after accepting it. Distinct from
 * {@link MessageStatus}, which is how far the outbox itself got: a message is
 * SENT the moment the provider takes it, and only these values say whether it
 * actually arrived.
 */
public enum DeliveryState {

	/** The receiving mail server accepted it. */
	DELIVERED,

	/** Permanently rejected. Terminal — and the reason an address is suppressed. */
	BOUNCED,

	/** Delivered, then marked as spam by the recipient. Terminal. */
	COMPLAINED,

	/** A temporary problem at the destination; the provider is still trying. */
	DELAYED,

	/** The provider itself could not send it after accepting. Terminal. */
	FAILED_AT_PROVIDER;

	/**
	 * Terminal states are conclusions about the message that later events cannot
	 * undo.
	 *
	 * DELIVERED is not one of them, but not because it is provisional: it is
	 * excluded so that bad news arriving afterwards — the second hop bouncing,
	 * the recipient complaining — can still land on it. What it will not accept
	 * is a step backwards; see {@link #supersedes}. DELAYED is the only state
	 * that is genuinely provisional.
	 */
	public boolean isTerminal() {
		return this == BOUNCED || this == COMPLAINED || this == FAILED_AT_PROVIDER;
	}

	/**
	 * Whether {@code incoming} should overwrite {@code current}.
	 *
	 * Webhooks are not ordered. A delivery attempt that bounced can produce a
	 * `delivered` for the first hop and a `bounced` for the second, and the two
	 * can arrive either way round; the provider also redelivers events it is not
	 * sure we received. So this cannot be a plain assignment — a late
	 * `delivered` overwriting a recorded `bounced` would resurrect an address
	 * the suppression list has already condemned, and the two would disagree.
	 *
	 * The rule: once a terminal state is recorded it stands, and a recorded
	 * DELIVERED yields only to bad news. Everything else — which is to say
	 * DELAYED, the one provisional state — is last-writer-wins.
	 */
	public static boolean supersedes(DeliveryState current, DeliveryState incoming) {
		if (current == null) {
			return true;
		}
		if (current.isTerminal()) {
			return false;
		}
		if (current == DELIVERED) {
			// A `delivery_delayed` that overtook the `delivered` it preceded is
			// still stale news. Walking the state back to "still trying" is the
			// oscillation this method exists to prevent.
			return incoming.isTerminal();
		}
		return current != incoming;
	}
}
