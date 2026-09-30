package ai.lambda.agent.core;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link SessionDatabase} for any SQL database with a JDBC driver: PostgreSQL, MySQL, MariaDB,
 * SQL Server, Oracle, H2, SQLite and others. Pass your application's {@link DataSource}; your
 * pool, driver and transaction settings are used, and no driver is bundled.
 *
 * <p>Three tables (the prefix is {@code lambda_} unless you choose another):
 * <pre>
 * lambda_sessions          session_id (key), version, metadata_json
 * lambda_session_messages  session_id, message_index (key together), message_json
 * lambda_session_media     session_id, media_name (key together), media_data
 * </pre>
 * Call {@link #initializeSchema()} to create missing tables, or create them with your own
 * migration tool (see docs/DatabaseSessions.md for the statements).
 */
public final class JdbcSessionDatabase implements SessionDatabase {

    private static final int MAX_SESSION_ID_LENGTH = 255;
    private static final int LOAD_ATTEMPTS = 5;

    private final DataSource dataSource;
    private final String sessions;
    private final String messages;
    private final String media;

    public JdbcSessionDatabase(DataSource dataSource) {
        this(dataSource, "lambda_");
    }

    /** @param tablePrefix put before each table name, such as {@code "lambda_"} or {@code "app."} for a schema */
    public JdbcSessionDatabase(DataSource dataSource, String tablePrefix) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (tablePrefix == null || !tablePrefix.matches("([A-Za-z_][A-Za-z0-9_]*\\.)?[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid table prefix: " + tablePrefix);
        }
        this.sessions = tablePrefix + "sessions";
        this.messages = tablePrefix + "session_messages";
        this.media = tablePrefix + "session_media";
    }

    /** Creates the tables that do not exist yet, with column types suited to the database. */
    public void initializeSchema() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String product = metaData.getDatabaseProductName().toLowerCase(Locale.ROOT);
            String text;
            String bytes;
            String bigint = "BIGINT";
            if (product.contains("postgres")) {
                text = "TEXT";
                bytes = "BYTEA";
            } else if (product.contains("mysql") || product.contains("mariadb")) {
                text = "LONGTEXT"; // TEXT holds only 64 KB
                bytes = "LONGBLOB";
            } else if (product.contains("microsoft") || product.contains("sql server")) {
                text = "NVARCHAR(MAX)";
                bytes = "VARBINARY(MAX)";
            } else if (product.contains("oracle")) {
                text = "CLOB";
                bytes = "BLOB";
                bigint = "NUMBER(19)";
            } else if (product.equals("h2")) {
                text = "CHARACTER LARGE OBJECT"; // accepted in every H2 compatibility mode
                bytes = "BINARY LARGE OBJECT";
            } else if (product.contains("sqlite")) {
                text = "TEXT";
                bytes = "BLOB";
            } else {
                text = "CLOB";
                bytes = "BLOB";
            }
            createIfMissing(connection, sessions, "CREATE TABLE " + sessions + " ("
                    + "session_id VARCHAR(255) NOT NULL PRIMARY KEY, "
                    + "version " + bigint + " NOT NULL, "
                    + "metadata_json " + text + " NOT NULL)");
            createIfMissing(connection, messages, "CREATE TABLE " + messages + " ("
                    + "session_id VARCHAR(255) NOT NULL, "
                    + "message_index INTEGER NOT NULL, "
                    + "message_json " + text + " NOT NULL, "
                    + "PRIMARY KEY (session_id, message_index))");
            createIfMissing(connection, media, "CREATE TABLE " + media + " ("
                    + "session_id VARCHAR(255) NOT NULL, "
                    + "media_name VARCHAR(80) NOT NULL, "
                    + "media_data " + bytes + " NOT NULL, "
                    + "PRIMARY KEY (session_id, media_name))");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize the session tables", e);
        }
    }

    private static void createIfMissing(Connection connection, String table, String createSql) throws SQLException {
        if (tableExists(connection, table)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(createSql);
        }
        if (!connection.getAutoCommit()) connection.commit();
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        int dot = table.indexOf('.');
        String schema = dot < 0 ? null : table.substring(0, dot);
        String name = table.substring(dot + 1);
        // Unquoted names are stored upper- or lower-case depending on the database.
        for (String candidateName : List.of(name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT))) {
            String candidateSchema = schema == null ? null
                    : metaData.storesUpperCaseIdentifiers() ? schema.toUpperCase(Locale.ROOT)
                    : metaData.storesLowerCaseIdentifiers() ? schema.toLowerCase(Locale.ROOT) : schema;
            try (ResultSet tables = metaData.getTables(null, candidateSchema, candidateName, null)) {
                if (tables.next()) return true;
            }
        }
        return false;
    }

    @Override
    public Optional<StoredSession> load(String sessionId) {
        checkId(sessionId);
        try (Connection connection = dataSource.getConnection()) {
            // The three reads are separate statements; if a save lands between them, read again.
            for (int attempt = 0; attempt < LOAD_ATTEMPTS; attempt++) {
                String[] row = sessionRow(connection, sessionId);
                if (row == null) return Optional.empty();
                long version = Long.parseLong(row[0]);
                List<String> messageJson = readMessages(connection, sessionId);
                Map<String, byte[]> mediaData = readMedia(connection, sessionId);
                String[] after = sessionRow(connection, sessionId);
                if (after != null && Long.parseLong(after[0]) == version) {
                    return Optional.of(new StoredSession(version, messageJson, row[1], mediaData));
                }
            }
            throw new RuntimeException("Session " + sessionId + " kept changing while it was loaded");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load session " + sessionId, e);
        }
    }

    private String[] sessionRow(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version, metadata_json FROM " + sessions + " WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new String[]{String.valueOf(result.getLong(1)), result.getString(2)} : null;
            }
        }
    }

    private List<String> readMessages(Connection connection, String sessionId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT message_json FROM " + messages + " WHERE session_id = ? ORDER BY message_index")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return result;
    }

    private Map<String, byte[]> readMedia(Connection connection, String sessionId) throws SQLException {
        Map<String, byte[]> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT media_name, media_data FROM " + media + " WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getBytes(2));
            }
        }
        return result;
    }

    @Override
    public void write(SessionChange change) {
        checkId(change.sessionId());
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                writeInTransaction(connection, change);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save session " + change.sessionId(), e);
        }
    }

    private void writeInTransaction(Connection connection, SessionChange change) throws SQLException {
        String id = change.sessionId();
        if (change.isNew()) {
            // A duplicate key means another instance created the session first.
            if (sessionRow(connection, id) != null) throw change.conflict();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + sessions + " (session_id, version, metadata_json) VALUES (?, ?, ?)")) {
                insert.setString(1, id);
                insert.setLong(2, change.newVersion());
                insert.setString(3, change.metadataJson());
                insert.executeUpdate();
            } catch (SQLException e) {
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) throw change.conflict();
                throw e;
            }
        } else {
            // Updating only at the expected version makes concurrent saves of one session take turns:
            // the second finds the version changed and updates nothing.
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + sessions
                    + " SET version = ?, metadata_json = ? WHERE session_id = ? AND version = ?")) {
                update.setLong(1, change.newVersion());
                update.setString(2, change.metadataJson());
                update.setString(3, id);
                update.setLong(4, change.expectedVersion());
                if (update.executeUpdate() != 1) throw change.conflict();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + messages + " WHERE session_id = ? AND message_index >= ?")) {
                delete.setString(1, id);
                delete.setInt(2, change.keptMessages());
                delete.executeUpdate();
            }
        }
        if (!change.newMessages().isEmpty()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + messages + " (session_id, message_index, message_json) VALUES (?, ?, ?)")) {
                int index = change.keptMessages();
                for (String json : change.newMessages()) {
                    insert.setString(1, id);
                    insert.setInt(2, index++);
                    insert.setString(3, json);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        if (!change.removedMedia().isEmpty()) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + media + " WHERE session_id = ? AND media_name = ?")) {
                for (String name : change.removedMedia()) {
                    delete.setString(1, id);
                    delete.setString(2, name);
                    delete.addBatch();
                }
                delete.executeBatch();
            }
        }
        if (!change.addedMedia().isEmpty()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + media + " (session_id, media_name, media_data) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, byte[]> entry : change.addedMedia().entrySet()) {
                    insert.setString(1, id);
                    insert.setString(2, entry.getKey());
                    insert.setBytes(3, entry.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private static void checkId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid session id (1 to " + MAX_SESSION_ID_LENGTH + " characters): " + sessionId);
        }
    }
}
