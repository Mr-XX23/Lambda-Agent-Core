package ai.lambda.agent.core;

import org.json.JSONObject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC checkpoint store. The supplied DataSource owns pooling and transaction
 * configuration; no JDBC driver is bundled by this module.
 */
public final class JdbcCheckpointStore implements CheckpointStore {
    private final DataSource dataSource;
    private final String tableName;

    public JdbcCheckpointStore(DataSource dataSource) {
        this(dataSource, "lambda_workflow_checkpoints");
    }

    public JdbcCheckpointStore(DataSource dataSource, String tableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid checkpoint table name");
        }
        this.tableName = tableName;
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "execution_id VARCHAR(255) PRIMARY KEY,"
                + "workflow_name VARCHAR(255) NOT NULL,"
                + "next_step INTEGER NOT NULL,"
                + "status VARCHAR(32) NOT NULL,"
                + "state_json TEXT NOT NULL,"
                + "error_text TEXT,"
                + "version BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException error) {
            throw failure("Failed to initialize checkpoint schema", error);
        }
    }

    @Override
    public Optional<WorkflowCheckpoint> load(String executionId) {
        String sql = "SELECT workflow_name, next_step, status, state_json, error_text, version "
                + "FROM " + tableName + " WHERE execution_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(read(executionId, result));
            }
        } catch (SQLException error) {
            throw failure("Failed to load checkpoint " + executionId, error);
        }
    }

    @Override
    public void save(WorkflowCheckpoint checkpoint) {
        saveInternal(checkpoint, null);
    }

    @Override
    public void save(WorkflowCheckpoint checkpoint, long expectedVersion) {
        saveInternal(checkpoint, expectedVersion);
    }

    private void saveInternal(WorkflowCheckpoint checkpoint, Long expectedVersion) {
        String selectSql = "SELECT version FROM " + tableName
                + " WHERE execution_id = ? FOR UPDATE";
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                select.setString(1, checkpoint.executionId());
                Long actual = null;
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) actual = result.getLong(1);
                }
                if (expectedVersion != null && !Objects.equals(actual, expectedVersion)) {
                    throw new OptimisticLockException(checkpoint.executionId(), expectedVersion,
                            actual == null ? 0 : actual);
                }
                if (actual == null) {
                    insert(connection, checkpoint);
                } else {
                    update(connection, checkpoint);
                }
                connection.commit();
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                if (error instanceof RuntimeException runtime) throw runtime;
                throw error;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException error) {
            throw failure("Failed to save checkpoint " + checkpoint.executionId(), error);
        }
    }

    private void insert(Connection connection, WorkflowCheckpoint checkpoint) throws SQLException {
        String sql = "INSERT INTO " + tableName
                + " (execution_id, workflow_name, next_step, status, state_json, error_text, version)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, checkpoint);
            statement.executeUpdate();
        }
    }

    private void update(Connection connection, WorkflowCheckpoint checkpoint) throws SQLException {
        String sql = "UPDATE " + tableName
                + " SET workflow_name = ?, next_step = ?, status = ?, state_json = ?, error_text = ?, version = ?"
                + " WHERE execution_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, checkpoint.workflowName());
            statement.setInt(2, checkpoint.nextStep());
            statement.setString(3, checkpoint.status().name());
            statement.setString(4, new JSONObject(checkpoint.state()).toString());
            statement.setString(5, checkpoint.error());
            statement.setLong(6, checkpoint.version());
            statement.setString(7, checkpoint.executionId());
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, WorkflowCheckpoint checkpoint) throws SQLException {
        statement.setString(1, checkpoint.executionId());
        statement.setString(2, checkpoint.workflowName());
        statement.setInt(3, checkpoint.nextStep());
        statement.setString(4, checkpoint.status().name());
        statement.setString(5, new JSONObject(checkpoint.state()).toString());
        statement.setString(6, checkpoint.error());
        statement.setLong(7, checkpoint.version());
    }

    private static WorkflowCheckpoint read(String executionId, ResultSet result) throws SQLException {
        Map<String, Object> state = new JSONObject(result.getString("state_json")).toMap();
        return new WorkflowCheckpoint(executionId, result.getString("workflow_name"),
                result.getInt("next_step"), WorkflowStatus.valueOf(result.getString("status")),
                state, result.getString("error_text"), result.getLong("version"));
    }

    private static RuntimeException failure(String message, SQLException error) {
        return new RuntimeException(message, error);
    }
}
