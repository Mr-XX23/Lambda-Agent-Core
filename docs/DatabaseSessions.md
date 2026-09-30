# Keeping sessions in your database

`JsonlSessionStore` keeps sessions in files on one machine. When several instances of your
application serve the same users (behind a load balancer, in Kubernetes, as serverless
functions), keep sessions in a database they all share with `DatabaseSessionStore`.

Lambda ships **no database drivers**. You use your own database, your own driver or client, and
your own connection settings. Lambda only needs a small bridge to it:

```
Agent ──> DatabaseSessionStore ──> SessionDatabase (the bridge) ──> your database
            converts messages,        two methods:                   Postgres, MySQL, SQL Server,
            metadata and media;       load, write                    Oracle, H2, MongoDB, Redis,
            saves only changes;                                      DynamoDB, Cassandra...
            detects conflicts
```

- **SQL:** the bridge is built in: `JdbcSessionDatabase` works with any JDBC `DataSource`.
- **NoSQL:** implement `SessionDatabase` (two methods) with your client. Complete, tested
  examples for MongoDB and Redis are in
  [`examples/database-sessions`](../examples/database-sessions/src/main/java/ai/lambda/examples/databasesessions).

## SQL databases

Add your database's JDBC driver to your application (for example `org.postgresql:postgresql`
or `com.mysql:mysql-connector-j`) and pass your `DataSource`, usually the pooled one your
application already has:

```java
JdbcSessionDatabase database = new JdbcSessionDatabase(dataSource);
database.initializeSchema();                       // creates missing tables; or use your migrations
Agent agent = new Agent(config, new DatabaseSessionStore(database));
```

In Spring Boot, inject the application's `DataSource`:

```java
@Bean
SessionStore sessionStore(DataSource dataSource) {
    JdbcSessionDatabase database = new JdbcSessionDatabase(dataSource);
    database.initializeSchema();
    return new DatabaseSessionStore(database);
}
```

`initializeSchema()` picks column types for PostgreSQL, MySQL/MariaDB, SQL Server, Oracle,
SQLite and H2 (including H2's PostgreSQL and MySQL modes). Tables are named `lambda_sessions`,
`lambda_session_messages` and `lambda_session_media`. Pass a prefix to change that, including
a schema: `new JdbcSessionDatabase(dataSource, "app.agent_")`.

To create the tables with Flyway or Liquibase instead, for PostgreSQL:

```sql
CREATE TABLE lambda_sessions (
  session_id    VARCHAR(255) NOT NULL PRIMARY KEY,
  version       BIGINT       NOT NULL,
  metadata_json TEXT         NOT NULL
);
CREATE TABLE lambda_session_messages (
  session_id    VARCHAR(255) NOT NULL,
  message_index INTEGER      NOT NULL,
  message_json  TEXT         NOT NULL,
  PRIMARY KEY (session_id, message_index)
);
CREATE TABLE lambda_session_media (
  session_id    VARCHAR(255) NOT NULL,
  media_name    VARCHAR(80)  NOT NULL,
  media_data    BYTEA        NOT NULL,
  PRIMARY KEY (session_id, media_name)
);
```

For MySQL/MariaDB use `LONGTEXT` and `LONGBLOB` (plain `TEXT` holds only 64 KB, less than a
large tool result). Session ids can be up to 255 characters.

## NoSQL databases

Implement two methods with the client you already use:

```java
public final class MySessionDatabase implements SessionDatabase {

    @Override
    public Optional<StoredSession> load(String sessionId) {
        // Read the session; return Optional.empty() if there is none.
        // StoredSession(version, messages as JSON strings, metadata JSON string, media bytes by name)
    }

    @Override
    public void write(SessionChange change) {
        // 1. If the stored version != change.expectedVersion() (0 = must not exist yet),
        //    throw change.conflict() and change nothing.
        // 2. Keep the first change.keptMessages() messages, drop the rest,
        //    append change.newMessages().
        // 3. Set version = change.newVersion(), metadata = change.metadataJson().
        // 4. Store change.addedMedia(), delete change.removedMedia().
    }
}
```

Then `new DatabaseSessionStore(new MySessionDatabase(client))`. Everything the bridge sees is
strings and bytes: the store turns messages, tool calls, provider state, metadata and media into
them and back.

Make the version check and the message update one atomic step so two instances cannot both
succeed. For example, a conditional update in MongoDB, `WATCH` with `MULTI`/`EXEC` in Redis, a
condition expression in DynamoDB, or a lightweight transaction in Cassandra. If the database
cannot make the media part of the same step, store added media before the messages and delete
removed media last, so messages never refer to missing media. See the
[MongoDB](../examples/database-sessions/src/main/java/ai/lambda/examples/databasesessions/MongoSessionDatabase.java)
and [Redis](../examples/database-sessions/src/main/java/ai/lambda/examples/databasesessions/RedisSessionDatabase.java)
bridges; the smallest possible one, over plain maps, is `MapSessionDatabase` in
`DatabaseSessionStoreTest`.

## What gets written

- **Only what changed.** The agent adds messages and never edits earlier ones, so a save sends
  just the messages added since the last save, plus the metadata. A save with nothing changed
  writes nothing. If you edit or remove earlier messages yourself, the store rewrites the
  session from the first changed message.
- **Media once.** Images, audio, video and documents are stored separately from the messages,
  once per session, under their SHA-256. Messages refer to them by name, and media no message
  uses any more is deleted.
- **One version per session.** It increases by one on every save.

## Two instances saving the same session

If two instances load the same session and both save, the second save throws
`OptimisticLockException` instead of silently dropping the first one's messages. This happens
when one user sends two requests at once that land on different instances. Handle it as fits
your application: tell the user to retry, or reload the session and run the request again.

Save a session through the store that loaded it. A store remembers what each session looked
like when it loaded it, which is how it knows what changed. A new `AgentSession` created for an
id that already exists is rejected instead of overwriting the stored one.
