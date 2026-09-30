package ai.lambda.examples.databasesessions;

import ai.lambda.agent.core.Agent;
import ai.lambda.agent.core.AgentConfig;
import ai.lambda.agent.core.DatabaseSessionStore;
import ai.lambda.agent.core.JdbcSessionDatabase;
import ai.lambda.agent.core.SessionDatabase;
import ai.lambda.ai.gemini.GeminiModelClient;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPool;

import java.net.URI;
import java.util.Scanner;

/**
 * A chat whose history lives in a database. Run it, quit, run it again: the conversation continues.
 *
 * <pre>
 * mvn -q exec:java -pl examples/database-sessions                          # H2 file, no setup
 * mvn -q exec:java -pl examples/database-sessions -Dexec.args=postgres     # DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD
 * mvn -q exec:java -pl examples/database-sessions -Dexec.args=mongodb      # MONGODB_URI (default mongodb://localhost:27017)
 * mvn -q exec:java -pl examples/database-sessions -Dexec.args=redis        # REDIS_URL (default redis://localhost:6379)
 * </pre>
 */
public class DatabaseSessionsChat {

    public static void main(String[] args) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }
        String kind = args.length > 0 ? args[0] : "h2";
        SessionDatabase database = switch (kind) {
            case "h2" -> {
                JdbcDataSource dataSource = new JdbcDataSource();
                dataSource.setURL("jdbc:h2:./.sessions-db/chat");
                yield sql(new JdbcSessionDatabase(dataSource));
            }
            case "postgres" -> {
                PGSimpleDataSource dataSource = new PGSimpleDataSource();
                dataSource.setURL(env("DATABASE_URL", "jdbc:postgresql://localhost:5432/postgres"));
                dataSource.setUser(env("DATABASE_USER", "postgres"));
                dataSource.setPassword(env("DATABASE_PASSWORD", ""));
                yield sql(new JdbcSessionDatabase(dataSource));
            }
            case "mongodb" -> {
                MongoClient client = MongoClients.create(env("MONGODB_URI", "mongodb://localhost:27017"));
                yield new MongoSessionDatabase(client.getDatabase("lambda"));
            }
            case "redis" -> new RedisSessionDatabase(new JedisPool(URI.create(env("REDIS_URL", "redis://localhost:6379"))));
            default -> throw new IllegalArgumentException("Use h2, postgres, mongodb or redis, not " + kind);
        };

        var config = new AgentConfig("You are Lambda, a concise helpful assistant.",
                new GeminiModelClient(apiKey, "gemini-3.1-flash-lite-preview"));
        var agent = new Agent(config, new DatabaseSessionStore(database));

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Lambda chat, history in " + kind + ". Type 'exit' to quit.");
            while (true) {
                System.out.print("You: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) break;
                System.out.println("Lambda: " + agent.run("session-1", line).getFinalText());
            }
        }
    }

    private static SessionDatabase sql(JdbcSessionDatabase database) {
        database.initializeSchema(); // or create the tables with your migration tool
        return database;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
