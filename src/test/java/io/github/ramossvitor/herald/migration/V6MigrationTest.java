package io.github.ramossvitor.herald.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V6 folds the email outbox into the channel-agnostic one. The rest of the
 * suite only ever sees it applied to an empty schema, so the copy itself —
 * the part that runs once, in production, against real rows — would otherwise
 * never be exercised.
 *
 * What must survive: the id (it is the provider idempotency key, so a message
 * in flight during the deploy has to keep the one the provider already saw),
 * the delivery state, and everything the quota gate counts.
 *
 * V7 is exercised in the same test because the two are one story: V6 parks the
 * old table so the deploy can be rolled back, V7 drops it once that window has
 * closed.
 */
@Testcontainers
class V6MigrationTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

	private static final UUID QUEUED = UUID.randomUUID();
	private static final UUID DEAD = UUID.randomUUID();
	private static final UUID DELIVERED = UUID.randomUUID();

	@Test
	void carriesEveryEmailRowIntoTheSharedOutbox() throws Exception {
		migrateTo("5");
		seedLegacyRows();

		migrateTo("6");

		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			// A message still queued keeps its identity and its place in the
			// retry ladder: same id, same attempt count, same idempotency key.
			try (ResultSet row = statement.executeQuery("""
					select channel, status, attempt_count, idempotency_key, sender,
					       payload->>'subject' as subject,
					       payload->>'html'    as html,
					       payload->>'text'    as text,
					       payload->>'replyTo' as reply_to,
					       limit_keys
					from messages where id = '%s'
					""".formatted(QUEUED))) {
				assertThat(row.next()).as("queued row survived").isTrue();
				assertThat(row.getString("channel")).isEqualTo("EMAIL");
				assertThat(row.getString("status")).isEqualTo("PENDING");
				assertThat(row.getInt("attempt_count")).isEqualTo(3);
				assertThat(row.getString("idempotency_key")).isEqualTo("invite:42");
				assertThat(row.getString("sender")).isEqualTo("Acme <mail@acme.example>");
				assertThat(row.getString("subject")).isEqualTo("Hello");
				assertThat(row.getString("html")).isEqualTo("<p>Hi</p>");
				assertThat(row.getString("text")).isEqualTo("Hi");
				assertThat(row.getString("reply_to")).isEqualTo("support@acme.example");
				assertThat((String[]) row.getArray("limit_keys").getArray())
						.containsExactly("inviter:7");
			}

			// A null reply_to becomes an absent key, not a JSON null: the two
			// read the same, and stripping keeps the payload honest about what
			// the caller actually sent.
			// `->` yields SQL NULL only when the key is absent; a stored JSON
			// null would come back as 'null'::jsonb instead. (The `?` containment
			// operator would work too, but JDBC reads it as a bind placeholder.)
			try (ResultSet row = statement.executeQuery(
					"select (payload->'replyTo') is null as missing_reply_to, status, last_error "
							+ "from messages where id = '%s'".formatted(DEAD))) {
				assertThat(row.next()).isTrue();
				assertThat(row.getBoolean("missing_reply_to")).isTrue();
				assertThat(row.getString("status")).isEqualTo("FAILED");
				assertThat(row.getString("last_error")).isEqualTo("http 422: validation_error");
			}

			// A delivered message keeps the provider's id and its sent_at, so
			// the status endpoint answers the same before and after the deploy.
			try (ResultSet row = statement.executeQuery(
					"select provider_message_id, sent_at from messages where id = '%s'".formatted(DELIVERED))) {
				assertThat(row.next()).isTrue();
				assertThat(row.getString("provider_message_id")).isEqualTo("re_123");
				assertThat(row.getTimestamp("sent_at")).isNotNull();
			}

			try (ResultSet count = statement.executeQuery("select count(*) from messages")) {
				count.next();
				assertThat(count.getInt(1)).isEqualTo(3);
			}

			// The old table is parked, not dropped: nothing answers to the old
			// name any more, and the rows are still there to roll back to until
			// V7 removes them.
			try (ResultSet names = statement.executeQuery("""
					select to_regclass('public.email_messages')        is not null as old_name,
					       to_regclass('public.email_messages_pre_v6') is not null as parked
					""")) {
				names.next();
				assertThat(names.getBoolean("old_name")).as("old name no longer resolves").isFalse();
				assertThat(names.getBoolean("parked")).as("rows kept for rollback").isTrue();
			}
			try (ResultSet count = statement.executeQuery("select count(*) from email_messages_pre_v6")) {
				count.next();
				assertThat(count.getInt(1)).isEqualTo(3);
			}
		}

		// V7 closes the rollback window. The copy has to survive it untouched.
		migrateTo("7");

		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			try (ResultSet gone = statement.executeQuery(
					"select to_regclass('public.email_messages_pre_v6') is null as dropped")) {
				gone.next();
				assertThat(gone.getBoolean("dropped")).isTrue();
			}
			try (ResultSet count = statement.executeQuery("select count(*) from messages")) {
				count.next();
				assertThat(count.getInt(1)).isEqualTo(3);
			}
		}
	}

	private void seedLegacyRows() throws Exception {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.execute("""
					insert into tenants (id, slug, name)
					values ('11111111-1111-1111-1111-111111111111', 'acme', 'Acme')
					""");
			statement.execute("""
					insert into tenant_email_settings (tenant_id, from_address, daily_limit, recipient_cooldown_seconds)
					values ('11111111-1111-1111-1111-111111111111', 'Acme <mail@acme.example>', 90, 600)
					""");
			statement.execute("""
					insert into email_messages (id, tenant_id, idempotency_key, recipient, recipient_canonical,
					                            from_address, subject, html_body, text_body, reply_to, limit_keys,
					                            status, attempt_count, next_attempt_at, last_error)
					values ('%s', '11111111-1111-1111-1111-111111111111', 'invite:42',
					        'Player <player@example.com>', 'player@example.com', 'Acme <mail@acme.example>',
					        'Hello', '<p>Hi</p>', 'Hi', 'support@acme.example', array['inviter:7'],
					        'PENDING', 3, now(), 'http 503: ')
					""".formatted(QUEUED));
			statement.execute("""
					insert into email_messages (id, tenant_id, recipient, recipient_canonical, from_address,
					                            subject, html_body, text_body, status, attempt_count, last_error)
					values ('%s', '11111111-1111-1111-1111-111111111111', 'bad@example.com', 'bad@example.com',
					        'Acme <mail@acme.example>', 'Hello', '<p>Hi</p>', 'Hi', 'FAILED', 1,
					        'http 422: validation_error')
					""".formatted(DEAD));
			statement.execute("""
					insert into email_messages (id, tenant_id, recipient, recipient_canonical, from_address,
					                            subject, html_body, text_body, status, attempt_count,
					                            provider_message_id, sent_at)
					values ('%s', '11111111-1111-1111-1111-111111111111', 'done@example.com', 'done@example.com',
					        'Acme <mail@acme.example>', 'Hello', '<p>Hi</p>', 'Hi', 'SENT', 1, 're_123', now())
					""".formatted(DELIVERED));
		}
	}

	private static void migrateTo(String version) {
		Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target(version)
				.load()
				.migrate();
	}

	private static Connection connect() throws Exception {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}
