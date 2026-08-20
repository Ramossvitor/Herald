package io.github.ramossvitor.herald.whatsapp;

/**
 * The template cannot carry this message. Refused at the door rather than
 * queued, because Meta would reject it too — and there the tenant would only
 * find out from a FAILED row, after the attempt was spent.
 */
public class TemplateNotUsableException extends RuntimeException {

	private final String template;
	private final String language;
	private final String templateStatus;

	private TemplateNotUsableException(String template, String language, String reason, String templateStatus) {
		super(reason);
		this.template = template;
		this.language = language;
		this.templateStatus = templateStatus;
	}

	public static TemplateNotUsableException unknown(String template, String language) {
		return new TemplateNotUsableException(template, language,
				"no such template for this tenant; it may not have synced yet", null);
	}

	public static TemplateNotUsableException notApproved(WhatsAppTemplate template) {
		String reason = template.getRejectedReason() != null
				? "template is " + template.getStatus() + ": " + template.getRejectedReason()
				: "template is " + template.getStatus() + ", not APPROVED";
		return new TemplateNotUsableException(template.getName(), template.getLanguage(), reason,
				template.getStatus());
	}

	/**
	 * Approved, but it asks for something Herald never sends. Meta would answer
	 * "missing parameter" on delivery, so the caller learns here instead.
	 */
	public static TemplateNotUsableException unsupportedParameters(WhatsAppTemplate template) {
		return new TemplateNotUsableException(template.getName(), template.getLanguage(),
				"template takes parameters in a header or a button URL, which Herald cannot supply; "
						+ "use a template whose only variables are in the body",
				template.getStatus());
	}

	public static TemplateNotUsableException wrongArity(WhatsAppTemplate template, int given) {
		return new TemplateNotUsableException(template.getName(), template.getLanguage(),
				"template takes " + template.getBodyParamCount() + " parameters, got " + given,
				template.getStatus());
	}

	public String template() {
		return template;
	}

	public String language() {
		return language;
	}

	public String reason() {
		return getMessage();
	}

	public String templateStatus() {
		return templateStatus;
	}
}
