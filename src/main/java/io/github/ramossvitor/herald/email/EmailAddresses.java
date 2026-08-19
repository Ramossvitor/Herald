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

	/**
	 * Extracts the addr-spec from either {@code a@b} or {@code Name <a@b>},
	 * lowercased and trimmed. Returns null when there is no parseable address —
	 * callers treat that as "not a verified sender", never as a crash.
	 */
	public static String addrSpec(String from) {
		if (from == null) {
			return null;
		}
		String candidate = from.trim();
		int open = candidate.lastIndexOf('<');
		int close = candidate.lastIndexOf('>');
		if (open >= 0 && close > open) {
			candidate = candidate.substring(open + 1, close).trim();
		}
		candidate = candidate.toLowerCase(Locale.ROOT);
		int at = candidate.lastIndexOf('@');
		if (at <= 0 || at == candidate.length() - 1 || candidate.contains(" ")) {
			return null;
		}
		return candidate;
	}

	/** The domain of an addr-spec produced by {@link #addrSpec}. */
	public static String domainOf(String addrSpec) {
		return addrSpec == null ? null : addrSpec.substring(addrSpec.lastIndexOf('@') + 1);
	}

	/**
	 * Canonical {@code Display Name <addr@spec>} (or a bare {@code addr@spec}),
	 * rebuilt from the parsed parts rather than echoed from the input. Returns
	 * null when the input is not one unambiguous address, which callers treat
	 * the same way as an unverified sender.
	 *
	 * Herald checks {@link #addrSpec} against the tenant's identities and then
	 * hands the from address to the provider, so the two have to agree: a value
	 * whose addr-spec another parser could read differently — a second
	 * angle-addr, an unquoted address in the display name, trailing text — is
	 * refused here instead of being forwarded unexamined.
	 */
	public static String normalizeFrom(String from) {
		if (from == null) {
			return null;
		}
		String candidate = from.trim();
		if (candidate.isEmpty() || hasControlCharacter(candidate)) {
			return null;
		}
		// A second angle-addr is the whole problem: addrSpec() reads the last
		// pair, a mail parser may well take the first.
		if (count(candidate, '<') > 1 || count(candidate, '>') > 1) {
			return null;
		}
		String addrSpec = addrSpec(candidate);
		if (addrSpec == null) {
			return null;
		}
		int open = candidate.indexOf('<');
		if (open < 0) {
			// Bare form: the address has to be the whole value, with nothing
			// alongside it that a parser might prefer.
			return candidate.equalsIgnoreCase(addrSpec) ? addrSpec : null;
		}
		if (!candidate.endsWith(">")) {
			return null;
		}
		return formatFrom(candidate.substring(0, open), addrSpec);
	}

	/**
	 * Builds a canonical from address, quoting the display name when RFC 5322
	 * requires it. Angle brackets are dropped from the name outright: they are
	 * never meaningful there and their absence is what keeps the address
	 * unambiguous to whatever parses it next.
	 */
	public static String formatFrom(String displayName, String addrSpec) {
		if (displayName == null) {
			return addrSpec;
		}
		String name = unquote(displayName.trim()).replace("<", "").replace(">", "").trim();
		if (name.isEmpty() || hasControlCharacter(name)) {
			return addrSpec;
		}
		return quoteIfNeeded(name) + " <" + addrSpec + ">";
	}

	/** RFC 5322 atext. A display name of only these (and spaces) is a bare
	 * phrase; anything else has to travel as a quoted-string. */
	private static boolean isAtext(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| "!#$%&'*+-/=?^_`{|}~".indexOf(c) >= 0;
	}

	private static String quoteIfNeeded(String displayName) {
		boolean bare = true;
		for (int i = 0; i < displayName.length(); i++) {
			char c = displayName.charAt(i);
			if (!isAtext(c) && c != ' ') {
				bare = false;
				break;
			}
		}
		if (bare) {
			return displayName;
		}
		StringBuilder quoted = new StringBuilder(displayName.length() + 2).append('"');
		for (int i = 0; i < displayName.length(); i++) {
			char c = displayName.charAt(i);
			if (c == '"' || c == '\\') {
				quoted.append('\\');
			}
			quoted.append(c);
		}
		return quoted.append('"').toString();
	}

	/** Unwraps an already-quoted display name so it is not quoted twice. */
	private static String unquote(String displayName) {
		if (displayName.length() < 2 || displayName.charAt(0) != '"'
				|| displayName.charAt(displayName.length() - 1) != '"') {
			return displayName;
		}
		String inner = displayName.substring(1, displayName.length() - 1);
		StringBuilder unescaped = new StringBuilder(inner.length());
		for (int i = 0; i < inner.length(); i++) {
			char c = inner.charAt(i);
			if (c == '\\' && i + 1 < inner.length()) {
				c = inner.charAt(++i);
			}
			unescaped.append(c);
		}
		return unescaped.toString();
	}

	private static boolean hasControlCharacter(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (Character.isISOControl(value.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	private static int count(String value, char c) {
		int total = 0;
		for (int i = 0; i < value.length(); i++) {
			if (value.charAt(i) == c) {
				total++;
			}
		}
		return total;
	}
}
