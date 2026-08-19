package io.github.ramossvitor.herald.sender;

/**
 * Delivery channels a message and a sender identity can belong to.
 *
 * WHATSAPP identities are bring-your-own: the tenant owns the number, the
 * WhatsApp Business Account and the templates, and Herald dispatches under
 * them. There is no shared tier as there is for email — a WhatsApp number
 * carries exactly one display name, so it cannot host several tenants the way
 * one domain hosts several mailboxes.
 */
public enum Channel {
	EMAIL,
	WHATSAPP
}
