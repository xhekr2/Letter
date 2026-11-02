package letter.server.http;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 키(예: IP)별로 최근 1분 동안의 요청 횟수를 제한한다. */
public class RateLimiter {
    private static final long WINDOW_MILLIS = 60_000;

    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Deque<Long>> history = new ConcurrentHashMap<>();

    public RateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = Objects.requireNonNull(clock);
    }

    /** 허용되면 요청을 기록하고 true, 한도를 넘었으면 false. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        Deque<Long> hits = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            dropExpired(hits, now);
            if (hits.size() >= limitPerMinute) return false;
            hits.addLast(now);
            return true;
        }
    }

    /** 오래된 기록을 정리해 메모리가 계속 늘지 않게 한다. 주기적으로 호출한다. */
    public void evictExpired() {
        long now = clock.millis();
        history.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                dropExpired(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    private static void dropExpired(Deque<Long> hits, long now) {
        while (!hits.isEmpty() && now - hits.peekFirst() > WINDOW_MILLIS) {
            hits.pollFirst();
        }
    }
}
