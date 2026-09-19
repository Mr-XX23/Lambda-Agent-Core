package ai.lambda.agent.core;

import java.util.Optional;

/** Minimal Redis command boundary; applications may bind Jedis, Lettuce, or another client. */
public interface RedisCheckpointClient {
    Optional<String> get(String key);
    boolean setIfAbsent(String key, String value);
    boolean compareAndSet(String key, String expectedValue, String value);
}
