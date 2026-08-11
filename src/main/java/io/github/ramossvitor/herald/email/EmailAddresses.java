package io.github.ramossvitor.herald.email;

import java.util.Locale;
import java.util.Set;

/**
 * Canonical form for comparison and quota windows only — the address stored
 * and sent to is always the one the caller provided.
 */
public final class EmailAddresses {

	/**
	 * On Gmail, dots in the local part are ignored and {@code +tag} is the same
	 * mailbox with a label. That is a Gmail rule, not an email rule — other
	 * providers treat {@code a.b@} and {@code ab@} as different people — so the
	 * set is deliberately narrow.
	 */
	private static final Set<String> DOT_INSENSITIVE_DOMAINS = Set.of("gmail.com", "googlemail.com");

	private EmailAddresses() {
	}

	public static String canonicalize(String email) {
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		int at = normalized.lastIndexOf('@');
		if (at <= 0) {
			return normalized;
		}

		String local = normalized.substring(0, at);
		String domain = normalized.substring(at + 1);
		if (!DOT_INSENSITIVE_DOMAINS.contains(domain)) {
			return normalized;
		}

		String withoutTag = local.split("\\+", 2)[0].replace(".", "");
		// A local part that was only dots and a tag would canonicalize to "",
		// making distinct addresses collide — the opposite of the point here.
		return withoutTag.isEmpty() ? normalized : withoutTag + "@" + domain;
	}
}
