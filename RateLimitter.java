package RateLimitter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public class RateLimitter {

    static class User {
        private final String userid;
        private final String name;

        public User(String userId, String name) {
            this.userid = userId;
            this.name = name;
        }

        public String getUserId() {return userid;}
        public String getName(){return name;}
    }

    enum Algorithm { FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET }
    static final class RateLimiterFactory {
        private RateLimiterFactory() {}

        static RateLimiter create(RateLimitConfig c) {
            return switch (c.algorithm()) {
                case FIXED_WINDOW -> new FixedWindowRateLimiter(c);
                case SLIDING_WINDOW -> new SlidingWindowLogRateLimiter(c);
                case TOKEN_BUCKET -> new TokenBucketRateLimiter(c);
            };
        }
    }
 
    record RateLimitConfig(Algorithm algorithm, int maxRequests, long windowMillis) {
        RateLimitConfig {
            if (algorithm == null) throw new IllegalArgumentException("algorithm required");
            if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be > 0");
            if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be > 0");
        }
    }

    record RateLimitResult(boolean allowed, int remaining, long retryAfterMillis, long retryAtEpochMillis) {
        public static RateLimitResult allow(int remaining) {
            return new RateLimitResult(true, remaining, 0, 0);
        }

        public static RateLimitResult deny(long retryAfterMillis, long nowMillis) {
            long retryAtEpochMillis = nowMillis + retryAfterMillis;
            return new RateLimitResult(false, 0, retryAfterMillis, retryAtEpochMillis);
        }
    }

    public interface RateLimiter {
        RateLimitResult tryAcquire(long nowMillis);
    }

    static class FixedWindowRateLimiter implements RateLimiter {
        private final int max;
        private final long window;
        private long windowStart = -1;
        private int count = 0;

        public FixedWindowRateLimiter(RateLimitConfig c) {
            this.max = c.maxRequests();
            this.window = c.windowMillis();
        }

        @Override 
        public synchronized RateLimitResult tryAcquire(long now){
            long currentStart = now - (now % window);
            if(currentStart != windowStart){
                windowStart = currentStart;
                count = 0;
            }
            if(count < max){
                count++;
                return RateLimitResult.allow(max - count);
            }
            return RateLimitResult.deny(windowStart + window - now, now);
        }
    }

    static class SlidingWindowLogRateLimiter implements RateLimiter {
        private final int max;
        private final long window;
        private final Deque<Long> timestamps = new ArrayDeque<>();
    
        SlidingWindowLogRateLimiter(RateLimitConfig c) {
            this.max = c.maxRequests();
            this.window = c.windowMillis();
        }
    
        @Override
        public synchronized RateLimitResult tryAcquire(long now) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= now - window) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < max) {
                timestamps.addLast(now);
                return RateLimitResult.allow(max - timestamps.size());
            }
            return RateLimitResult.deny(timestamps.peekFirst() + window - now, now);
        }
    }

    static class TokenBucketRateLimiter implements RateLimiter {
        private final double capacity;
        private final double refillPerMilli;
        private double tokens;
        private long lastRefill = -1;
    
        TokenBucketRateLimiter(RateLimitConfig c) {
            this.capacity = c.maxRequests();
            this.refillPerMilli = (double) c.maxRequests() / c.windowMillis();
            this.tokens = capacity;
        }
    
        @Override
        public synchronized RateLimitResult tryAcquire(long now) {
            if (lastRefill < 0) lastRefill = now;
            long elapsed = Math.max(0, now - lastRefill);
            tokens = Math.min(capacity, tokens + elapsed * refillPerMilli);
            lastRefill = now;
    
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return RateLimitResult.allow((int) tokens);
            }
            long wait = (long) Math.ceil((1.0 - tokens) / refillPerMilli);
            return RateLimitResult.deny(wait, now);
        }
    }

    static class RateLimiterService{
        private final ConcurrentMap<String, RateLimitConfig> userConfigs = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
        private final RateLimitConfig defaultConfig;
        private final LongSupplier clock;

        public RateLimiterService(RateLimitConfig defaultConfig, LongSupplier clock) {
            this.defaultConfig = defaultConfig;
            this.clock = clock;
        }

        public void configureUser(User user, RateLimitConfig config) {
            Objects.requireNonNull(user, "user cannot be null");
            Objects.requireNonNull(config, "config cannot be null");

            userConfigs.put(user.getUserId(), config);
            limiters.remove(user.getUserId());
        }

        RateLimitResult allowRequest(User user) {
            Objects.requireNonNull(user, "user cannot be null");
            String userId = user.getUserId();
            RateLimiter limiter = limiters.computeIfAbsent(
                    userId, id -> RateLimiterFactory.create(userConfigs.getOrDefault(id, defaultConfig)));

            return limiter.tryAcquire(clock.getAsLong());
        }

        RateLimitConfig getRateLimitConfig(User user){
            return userConfigs.get(user.getUserId());
        }


    }

    public static void main(String[] args) {
        AtomicLong now = new AtomicLong(10); 
        RateLimiterService service = new RateLimiterService(new RateLimitConfig(Algorithm.FIXED_WINDOW, 5, 10),now::get);

        User user1 = new User("user-1", "Abe");
        User user2 = new User("user-2", "Bob");
        User user3 = new User("user-3", "Chris");

        service.configureUser(
            user1,
            new RateLimitConfig(Algorithm.FIXED_WINDOW, 5, 10)
        );
        service.configureUser(
            user2,
            new RateLimitConfig(Algorithm.SLIDING_WINDOW, 5, 10)
        );
        service.configureUser(
            user3,
            new RateLimitConfig(Algorithm.TOKEN_BUCKET, 5, 10)
        );

        // Fixed window: first five requests pass, sixth is denied.
        for (int i = 1; i <= 6; i++) {
            verify("Fixed window request " + i, service.allowRequest(user1),true);
            now.addAndGet(2);
            System.out.println(service.getRateLimitConfig(user1));
        }
        // verify("Fixed window request 6 (limit reached)",service.allowRequest(user1), false);
        //     // Advance to the next fixed window.
        // now.addAndGet(10);
        // verify("Fixed window after reset", service.allowRequest(user1), true);
    }

    private static void verify(
        String label,
        RateLimitResult result,
        boolean expectedAllowed) {

        boolean testPassed = result.allowed() == expectedAllowed;

        System.out.printf(
                "%-35s allowed=%-5s remaining=%d retryAfter=%dms test=%s%n",
                label,
                result.allowed(),
                result.remaining(),
                result.retryAfterMillis(),
                testPassed ? "PASS" : "FAIL"
        );

        if (!testPassed) {
            throw new AssertionError(label + " Request not allowed");
        }
    }
    
}
// javac RateLimitter/RateLimitter.java && java RateLimitter.RateLimitter
