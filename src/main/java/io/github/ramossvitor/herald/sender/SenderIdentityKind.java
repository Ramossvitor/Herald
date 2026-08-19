package io.github.ramossvitor.herald.sender;

public enum SenderIdentityKind {
	/** A single address on the operator's shared root domain (slug@send.root). */
	EMAIL_SHARED_ADDRESS,
	/** A whole domain the tenant owns; any address at it may send once verified. */
	EMAIL_CUSTOM_DOMAIN
}
