# Interview Notes

## Two-Minute Pitch

I built a small Spring Boot API gateway demo that protects a sample endpoint with API keys and a per-client token bucket. Each bucket starts with a configured capacity, refills using elapsed monotonic time, and consumes a token as part of one lock-protected operation. A request that cannot get a token receives a 429 response with retry and rate-limit headers.

I kept the project deliberately local and explainable: configured demo clients, in-memory counters, an admin-protected monitoring API, and a vanilla dashboard. The dashboard displays totals and a bounded five-minute traffic series and can send a live burst to demonstrate enforcement. I tested edge cases with a controllable clock, including fractional refill and 200 simultaneous consumers racing for 50 tokens. The current `mvnw.cmd clean verify` run completed with seven tests and no failures. This is a single-process learning project; a real multi-instance service would need shared atomic state and operational defenses around the API.

## Design Decisions and Trade-offs

- **Token bucket:** permits short bursts while enforcing a long-term average rate. A fixed window is simpler but can allow a boundary burst; a sliding window gives smoother limits at a higher state/compute cost.
- **Per-bucket lock:** the critical section keeps elapsed refill, capacity capping, and consumption indivisible. Different clients use different locks, so one busy client does not serialize every client.
- **Monotonic time:** `System.nanoTime()` measures elapsed durations and is not changed by NTP or manual wall-clock updates. Wall time remains appropriate for display timestamps.
- **Fractional refill:** keep a `double` token balance; floor only when reporting whole tokens. Retry/reset seconds use ceiling so clients do not retry early.
- **LongAdder counters:** good throughput for concurrent aggregate statistics; snapshots are sums and are not a cross-counter transactional snapshot.
- **Bounded unknown-key handling:** missing and invalid keys update aggregate counters only. Unknown key material is neither logged nor stored by client value.
- **Admin authentication:** compare fixed-size SHA-256 digests using `MessageDigest.isEqual`, then return one generic failure message.
- **Public endpoint:** deliberately unlimited, but every request is included in aggregate and time-series statistics.

## Concurrency Correctness

`TokenBucket.tryConsume()` holds that bucket's `ReentrantLock` around refill and consume. Consequently, two requests cannot both observe and consume the same last token. A frozen fake clock ensures the unit race test has no refill; 200 tasks are released together by a `CountDownLatch`, and exactly 50 succeed on each of three repetitions. This demonstrates correctness within one JVM, not across multiple application instances.

## Security Discussion

- **Key handling:** demo keys are fake and config-overridable. Production needs secret-manager provisioning, rotation, scoped credentials, and a decision about hashing/storage.
- **Constant-time comparison:** the monitoring key comparison uses `MessageDigest.isEqual` on fixed-length digests; the response does not distinguish missing from wrong values.
- **Log hygiene:** the filter logs client IDs for rate-limit events and uses a request ID in MDC. It never logs supplied key strings.
- **Memory exhaustion:** invalid API keys never become map keys. The time-series ring has a fixed 60 slots, and configured-client counters are bounded by configured clients.
- **DoS:** API-key validation and this in-memory limiter do not stop connection floods or invalid-key floods. Add upstream IP/rate controls, body and connection limits, timeouts, monitoring, and a WAF/reverse proxy.
- **Tenant isolation:** a valid key resolves to a configured client with an independent bucket and statistics row. Keep configuration and credentials isolated per tenant in a real service.
- **Deployment:** this sample has no TLS, persistence, distributed coordination, or production secret lifecycle.

## Likely Interview Questions

1. **Why token bucket?** It allows useful bursts while enforcing a refill-defined sustained rate.
2. **How is the operation atomic?** One per-bucket lock encloses refill, test, consume, and decision values.
3. **Why not synchronize the whole service?** Separate locks let independent clients proceed concurrently.
4. **Why monotonic time?** Wall-clock adjustments must not add or remove elapsed refill time.
5. **How do you calculate retry time?** Remaining fraction to one token divided by the refill rate, rounded up to seconds.
6. **Why round up?** A client must not retry before the needed token is available.
7. **How do you prevent overfill?** Clamp the balance to capacity after elapsed-time refill.
8. **What handles fractional refill?** A floating-point token balance retains partial tokens between requests.
9. **What does the concurrency test prove?** For a frozen clock and one bucket, exactly the initial token count can succeed under a simultaneous race.
10. **What does it not prove?** Distributed correctness across JVMs or behavior under real production load.
11. **Why use LongAdder?** It reduces contention for hot counters; each summary read is an aggregate, not a globally atomic snapshot.
12. **How are invalid keys represented?** Only by an aggregate counter, so unique attacker input does not grow memory.
13. **Why generic auth errors?** They avoid making key validity distinctions part of the response contract.
14. **How is the admin key compared?** Fixed-size digests are checked with `MessageDigest.isEqual`.
15. **What information is returned to monitoring?** IDs and statistics only; never API/admin key values.
16. **Why count the public endpoint?** It provides a visible baseline while remaining explicitly unlimited in this demo.
17. **Why a ring buffer?** It bounds time-series memory while keeping recent traffic available for the chart.
18. **How would you scale to multiple nodes?** Use a shared store with an atomic operation, such as Redis Lua, and define failure behavior.
19. **When would a sliding window be preferable?** When smoother enforcement matters more than burst flexibility.
20. **What would you change before production?** TLS, managed secrets, upstream DoS controls, shared state, stronger observability, configuration validation, and deployment-specific threat review.

## Code Walkthrough Order

1. `config/RateLimiterProperties.java` and `application.yml`: client identities, capacities, rates, and demo secrets.
2. `util/TimeSource.java` and `service/TokenBucket.java`: deterministic time and the atomic bucket operation.
3. `service/RateLimiterService.java`: API-key lookup and client-to-bucket mapping.
4. `filter/ApiSecurityFilter.java`: admin authentication, API authentication, rate decisions, headers, and rejection accounting.
5. `service/StatsService.java`: aggregate counters, configured-client counters, and the fixed ring buffer.
6. Controllers and `GlobalExceptionHandler.java`: response contracts and stable JSON errors.
7. `ApiIntegrationTest.java` and `TokenBucketTest.java`: deterministic behavior and HTTP assertions.
8. `static/index.html`, `styles.css`, and `app.js`: monitoring and demonstration experience.

## Resume Bullets

- Implemented a Java 25/Spring Boot token-bucket API limiter with per-client configuration, atomic bucket updates, monotonic refill, and rate-limit response headers.
- Added admin-protected traffic monitoring with bounded time-series storage, aggregate invalid-key accounting, and secret-free client statistics.
- Verified token refill, edge cases, and a 200-task concurrency race with a fake clock; `mvnw.cmd clean verify` passed 7 tests.
- Built a responsive vanilla-JavaScript dashboard with auto-refresh, Canvas traffic chart, and a live 20-request rate-limit demonstration.

## Next Step for Distributed Deployment

Replace local buckets with a Redis token-bucket operation implemented as one Lua script that reads the bucket, calculates elapsed refill, caps capacity, and consumes a token atomically. Decide key expiry, Redis outage behavior, cluster time source, and migration strategy; add multi-instance integration tests and operational metrics before claiming distributed guarantees.