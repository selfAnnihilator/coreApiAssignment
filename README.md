# Grid07 Backend Engineering Assignment

Spring Boot microservice — API gateway with Redis guardrails and event-driven notification batching.

## Stack

- Java 17 / Spring Boot 3.2
- PostgreSQL 15 (source of truth)
- Redis 7 (guardrails + virality + notifications)

## Quick Start

```bash
# Start Postgres + Redis
docker-compose up -d

# Run the service
./mvnw spring-boot:run
```

Server starts on `http://localhost:8080`.

---

## Architecture

### Phase 1 — Core API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/users` | Create user |
| POST | `/api/bots` | Create bot |
| POST | `/api/posts` | Create post |
| POST | `/api/posts/{id}/comments` | Add comment |
| POST | `/api/posts/{id}/like` | Like a post |
| GET  | `/api/posts/{id}/virality` | Get virality score |

### Phase 2 — Virality & Atomic Locks

**Virality scoring** (Redis key `post:{id}:virality_score`):

| Event | Points |
|-------|--------|
| Bot reply | +1 |
| Human like | +20 |
| Human comment | +50 |

**Guardrails enforced before every bot comment:**

1. **Horizontal Cap** — max 100 bot replies per post (`post:{id}:bot_count`)
2. **Vertical Cap** — max depth 20 (`depth_level > 20` → rejected)
3. **Cooldown Cap** — bot cannot interact with the same human more than once per 10 minutes (`cooldown:bot_{id}:human_{id}` with 10-min TTL)

### Phase 3 — Notification Engine

- First bot interaction per user per 15 minutes → immediate log + cooldown set
- Subsequent interactions within 15 min → queued in Redis List (`user:{id}:pending_notifs`)
- `@Scheduled` CRON sweeper runs every 5 minutes, pops all pending messages per user, logs a summarized notification, clears the list

---

## Thread Safety for Atomic Locks (Phase 2)

Two race conditions exist; both are solved with atomic Redis operations.

### Race 1: Cooldown Cap

Naive approach (EXISTS → SET) has a window where 200 concurrent threads all see "no key" and all pass.

**Solution**: `SET NX EX` (`setIfAbsent` in Spring Data Redis) — check and set are one indivisible Redis operation. Exactly one thread wins; all others get `false` → 429.

If the subsequent horizontal cap check rejects, the cooldown key is deleted so the bot isn't unfairly penalized.

### Race 2: Horizontal Cap

200 concurrent bot requests could each read `bot_count = 99`, all pass the check, then all INCR — ending up at 299.

### Solution: Redis Lua Script

```lua
local count = redis.call('INCR', KEYS[1])
if count > tonumber(ARGV[1]) then
    redis.call('DECR', KEYS[1])
    return -1
end
return count
```

Redis executes Lua scripts **atomically** — the entire script runs as a single unit with no other commands interleaved. This collapses the read-check-write into one indivisible operation:

- Thread sees result `-1` → guardrail rejects with HTTP 429
- Thread sees result `1..100` → allowed, proceeds to DB write
- No two threads can ever both see a result ≤ 100 when the real count is already 100

### Rollback on DB failure

If the Lua script increments the counter but the subsequent DB `INSERT` fails (e.g., constraint violation, network error), `GuardrailService.releaseBotSlot()` decrements the counter, keeping Redis and Postgres consistent.

### Statelessness guarantee

No `HashMap`, `AtomicInteger`, or `static` variables are used anywhere. All state lives in Redis:

| Data | Redis key |
|------|-----------|
| Bot reply count | `post:{id}:bot_count` |
| Cooldown | `cooldown:bot_{id}:human_{id}` (TTL 10 min) |
| Notification cooldown | `notif:cooldown:{userId}` (TTL 15 min) |
| Pending notifications | `user:{id}:pending_notifs` (List) |
| Users with pending notifs | `users:pending:notifs` (Set) |
| Virality score | `post:{id}:virality_score` |

Multiple app instances can run behind a load balancer and share the same Redis — the Lua script guarantees correctness regardless of which instance handles each request.

---

## Running Tests

```bash
mvn test
```

35 unit tests, no Docker or running app required.

| Suite | Tests | Covers |
|-------|-------|--------|
| `GuardrailServiceTest` | 14 | All 3 guardrails, edge cases, rollbacks |
| `ViralityServiceTest` | 6 | Point values (+1/+20/+50), key format |
| `NotificationServiceTest` | 4 | Throttle logic, pending queue format |
| `PostServiceTest` | 11 | Full comment/like flows, DB rollback |
