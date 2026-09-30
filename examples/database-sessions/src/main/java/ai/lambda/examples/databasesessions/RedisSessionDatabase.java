package ai.lambda.examples.databasesessions;

import ai.lambda.agent.core.SessionChange;
import ai.lambda.agent.core.SessionDatabase;
import ai.lambda.agent.core.StoredSession;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sessions in Redis. Copy it into your application and adjust names as you like.
 *
 * <p>Three keys per session: a hash with the version and metadata, a list of messages, and a
 * hash of media bytes. A save runs in one MULTI/EXEC transaction under WATCH, so it is atomic
 * and fails as a conflict if another instance saved the session in between.
 */
public final class RedisSessionDatabase implements SessionDatabase {

    private final JedisPool pool;
    private final String prefix;

    public RedisSessionDatabase(JedisPool pool) {
        this(pool, "lambda:session:");
    }

    public RedisSessionDatabase(JedisPool pool, String prefix) {
        this.pool = pool;
        this.prefix = prefix;
    }

    private String infoKey(String id) {
        return prefix + id;
    }

    private String messagesKey(String id) {
        return prefix + id + ":messages";
    }

    private byte[] mediaKey(String id) {
        return (prefix + id + ":media").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Optional<StoredSession> load(String sessionId) {
        try (Jedis jedis = pool.getResource()) {
            // Read the three keys in one transaction so they come from the same save.
            Transaction read = jedis.multi();
            Response<Map<String, String>> info = read.hgetAll(infoKey(sessionId));
            Response<List<String>> messages = read.lrange(messagesKey(sessionId), 0, -1);
            Response<Map<byte[], byte[]>> media = read.hgetAll(mediaKey(sessionId));
            read.exec();
            if (info.get().isEmpty()) return Optional.empty();
            Map<String, byte[]> files = new HashMap<>();
            media.get().forEach((name, data) -> files.put(new String(name, StandardCharsets.UTF_8), data));
            return Optional.of(new StoredSession(Long.parseLong(info.get().get("version")), messages.get(),
                    info.get().get("metadata"), files));
        }
    }

    @Override
    public void write(SessionChange change) {
        String id = change.sessionId();
        try (Jedis jedis = pool.getResource()) {
            jedis.watch(infoKey(id));
            String stored = jedis.hget(infoKey(id), "version");
            if ((stored == null ? 0 : Long.parseLong(stored)) != change.expectedVersion()) {
                jedis.unwatch();
                throw change.conflict();
            }
            Transaction save = jedis.multi();
            if (change.keptMessages() == 0) save.del(messagesKey(id));
            else save.ltrim(messagesKey(id), 0, change.keptMessages() - 1);
            if (!change.newMessages().isEmpty()) save.rpush(messagesKey(id), change.newMessages().toArray(String[]::new));
            save.hset(infoKey(id), Map.of("version", String.valueOf(change.newVersion()), "metadata", change.metadataJson()));
            change.addedMedia().forEach((name, data) -> save.hset(mediaKey(id), name.getBytes(StandardCharsets.UTF_8), data));
            if (!change.removedMedia().isEmpty()) {
                save.hdel(mediaKey(id), change.removedMedia().stream()
                        .map(name -> name.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new));
            }
            List<Object> results = save.exec();
            if (results == null || results.isEmpty()) throw change.conflict(); // WATCH saw another save
        }
    }
}
