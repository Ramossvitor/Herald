package io.github.ramossvitor.herald.whatsapp;

/**
 * E.164 in, digits out.
 *
 * Herald refuses to guess a country code. A bare "11999990000" is a São Paulo
 * mobile to a Brazilian reader and something else entirely to the routing
 * layer, and a wrong guess here does not fail — it delivers, to a stranger, and
 * bills for it. Callers send the number the way E.164 writes it, with the plus.
 */
public final class PhoneNumbers {

	/** E.164 allows at most 15 digits; the country code makes 8 a sane floor. */
	private static final int MIN_DIGITS = 8;
	private static final int MAX_DIGITS = 15;

	private PhoneNumbers() {
	}

	/**
	 * The form stored in {@code recipient_canonical} and sent to Meta: digits
	 * only, no plus, no separators. Two spellings of one number therefore share
	 * a quota window.
	 *
	 * @return null when the input is not a well-formed E.164 number
	 */
	public static String canonicalize(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (!trimmed.startsWith("+")) {
			return null;
		}
		StringBuilder digits = new StringBuilder(MAX_DIGITS);
		for (int i = 1; i < trimmed.length(); i++) {
			char character = trimmed.charAt(i);
			if (Character.isDigit(character)) {
				digits.append(character);
			}
			// Only the separators a human would type are tolerated; anything
			// else means the caller sent something that is not a phone number.
			else if (character != ' ' && character != '-' && character != '(' && character != ')'
					&& character != '.') {
				return null;
			}
		}
		if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
			return null;
		}
		// A country code never starts at zero, so a leading one means a national
		// trunk prefix survived — exactly the ambiguity this refuses to resolve.
		if (digits.charAt(0) == '0') {
			return null;
		}
		return digits.toString();
	}
}
