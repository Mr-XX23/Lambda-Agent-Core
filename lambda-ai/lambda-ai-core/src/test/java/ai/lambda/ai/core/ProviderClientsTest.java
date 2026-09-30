package ai.lambda.ai.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderClientsTest {

    private static final HttpOptions OPTIONS = HttpOptions.defaults();

    @Test
    void theSameSettingsShareOneClient() {
        AtomicInteger created = new AtomicInteger();
        Object first = ProviderClients.shared("test-share", "key-1", OPTIONS, null, () -> new Object[]{created.incrementAndGet()});
        Object second = ProviderClients.shared("test-share", "key-1", OPTIONS, null, () -> new Object[]{created.incrementAndGet()});

        assertSame(first, second);
        assertEquals(1, created.get());
    }

    @Test
    void anythingDifferentGetsItsOwnClient() {
        Object base = ProviderClients.shared("test-apart", "key-1", OPTIONS, null, Object::new);
        HttpOptions slower = new HttpOptions(Duration.ofSeconds(30), Duration.ofMinutes(5), 1, Duration.ZERO);

        assertNotSame(base, ProviderClients.shared("test-apart", "key-2", OPTIONS, null, Object::new), "another key");
        assertNotSame(base, ProviderClients.shared("test-apart", "key-1", slower, null, Object::new), "other settings");
        assertNotSame(base, ProviderClients.shared("test-apart", "key-1", OPTIONS, "http://proxy", Object::new), "another address");
        assertNotSame(base, ProviderClients.shared("test-apart-2", "key-1", OPTIONS, null, Object::new), "another provider");
        assertSame(base, ProviderClients.shared("test-apart", "key-1", new HttpOptions(OPTIONS.connectTimeout(),
                OPTIONS.requestTimeout(), OPTIONS.maxAttempts(), OPTIONS.initialBackoff()), null, Object::new), "equal settings");
    }

    @Test
    void keepsAtMostTheLimitAndDropsTheLeastRecentlyUsed() {
        Object kept = ProviderClients.shared("test-bound", "hot", OPTIONS, null, Object::new);
        for (int i = 0; i < ProviderClients.MAX_CLIENTS * 2; i++) {
            ProviderClients.shared("test-bound", "cold-" + i, OPTIONS, null, Object::new);
            ProviderClients.shared("test-bound", "hot", OPTIONS, null, Object::new); // keep it in use
        }

        assertTrue(ProviderClients.size() <= ProviderClients.MAX_CLIENTS, "size " + ProviderClients.size());
        assertSame(kept, ProviderClients.shared("test-bound", "hot", OPTIONS, null, Object::new), "a client in use stays");
        AtomicInteger recreated = new AtomicInteger();
        ProviderClients.shared("test-bound", "cold-0", OPTIONS, null, () -> {
            recreated.incrementAndGet();
            return new Object();
        });
        assertEquals(1, recreated.get(), "an old, unused client was dropped and is created again");
    }

    @Test
    void rejectsMissingArguments() {
        assertThrows(NullPointerException.class, () -> ProviderClients.shared(null, "k", OPTIONS, null, Object::new));
        assertThrows(NullPointerException.class, () -> ProviderClients.shared("p", "k", OPTIONS, null, null));
        assertThrows(NullPointerException.class, () -> ProviderClients.shared("test-null", "k", OPTIONS, null, () -> null));
    }
}
