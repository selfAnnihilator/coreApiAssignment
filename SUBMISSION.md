# Submission Report — Grid07 Backend Engineering Assignment

---

## PDF Requirements vs Implementation

### Phase 1 — Core API & Database Setup

| Requirement | Status | Location |
|---|---|---|
| User entity: `id, username, is_premium` | Done | `entity/User.java` |
| Bot entity: `id, name, persona_description` | Done | `entity/Bot.java` |
| Post entity: `id, author_id, content, created_at` | Done | `entity/Post.java` |
| Comment entity: `id, post_id, author_id, content, depth_level, created_at` | Done | `entity/Comment.java` |
| `author_id` can be User or Bot | Done | `AuthorType` enum on Post + Comment |
| `POST /api/posts` | Done | `controller/PostController.java:28` |
| `POST /api/posts/{postId}/comments` | Done | `controller/PostController.java:33` |
| `POST /api/posts/{postId}/like` | Done | `controller/PostController.java:39` |
| Docker for Postgres + Redis | Done | `docker-compose.yml` |

---

### Phase 2 — Redis Virality Engine & Atomic Locks

#### Virality Score

| Rule | Points | Redis key | Status |
|---|---|---|---|
| Bot Reply | +1 | `post:{id}:virality_score` | Done |
| Human Like | +20 | `post:{id}:virality_score` | Done |
| Human Comment | +50 | `post:{id}:virality_score` | Done |

All updates use `INCRBY` — atomic by design, no race condition possible.
See `service/ViralityService.java`.

#### Atomic Locks (Concurrency Protection)

| Guardrail | Limit | Redis key | Rejection | Status |
|---|---|---|---|---|
| Horizontal Cap | 100 bot replies/post | `post:{id}:bot_count` | HTTP 429 | Done |
| Vertical Cap | depth > 20 | (no Redis key needed) | HTTP 429 | Done |
| Cooldown Cap | 1 interaction/10 min | `cooldown:bot_{id}:human_{id}` TTL 10 min | HTTP 429 | Done |

**Thread safety detail**: Horizontal cap uses a Redis Lua script (defined in `config/RedisConfig.java`) that atomically INCRs and checks in a single operation. No read-modify-write gap. Script returns `-1` if cap would be exceeded — at that point the counter is left unchanged.

See `service/GuardrailService.java`.

---

### Phase 3 — Notification Engine (Smart Batching)

| Requirement | Status | Location |
|---|---|---|
| Check if user has received notification in last 15 min | Done | `service/NotificationService.java` |
| If YES: push message to `user:{id}:pending_notifs` | Done | `service/NotificationService.java:35` |
| If NO: log "Push Notification Sent to User" + set 15-min cooldown | Done | `service/NotificationService.java:39` |
| `@Scheduled` sweeper every 5 minutes | Done | `scheduler/NotificationSweeper.java:33` |
| Scan all users with pending notifications | Done | via `users:pending:notifs` Redis Set |
| Pop all messages, count, log summarized message | Done | `scheduler/NotificationSweeper.java:58-71` |
| Clear Redis list for each user | Done | `scheduler/NotificationSweeper.java:74` |

Exact log format produced by sweeper:
```
Summarized Push Notification: Bot X and [N] others interacted with your posts.
```

---

### Phase 4 — Corner Cases

| Requirement | How it's handled |
|---|---|
| Race condition (200 concurrent bots) | Lua script in `RedisConfig.atomicIncrIfBelowCapScript` — single atomic Redis op, impossible to exceed 100 |
| Statelessness | Zero `HashMap`, `static` mutable fields, or instance state — all counters/TTLs live in Redis |
| Data integrity | In `PostService.addComment`: Redis guardrail runs BEFORE `@Transactional` DB write; if guardrail throws, no DB transaction opens; if DB write fails after guardrail passed, `releaseBotSlot()` decrements the Redis counter |

---

### Deliverables

| Item | Status | File |
|---|---|---|
| Spring Boot source code | Done | `src/` |
| `docker-compose.yml` | Done | root |
| Postman collection | Done | `postman_collection.json` |
| README with thread-safety explanation | Done | `README.md` |
| Git repository | Done | initialized, all source committed |

---

## Extra / Out-of-Spec Additions

These were added beyond the PDF requirements to make the service fully usable and testable:

### 1. User & Bot CRUD endpoints
- `POST /api/users` and `POST /api/bots` — the PDF only specifies Post/Comment/Like endpoints but the guardrails require real User and Bot IDs to exist in Postgres. These endpoints let you seed data without SQL.
- `GET /api/users`, `GET /api/bots` — list endpoints for inspection.

### 2. Virality score read endpoint
- `GET /api/posts/{postId}/virality` — reads current `post:{id}:virality_score` from Redis. Not in spec but needed to verify scoring works during testing.

### 3. `parentCommentId` on Comment
- The spec defines `depth_level` but does not specify how depth is tracked. Added `parent_comment_id` column to `comments` table so each comment knows its parent. This is how `depth_level` is computed: `parent.depthLevel + 1`. Top-level comments have `parent_comment_id = null` and `depth_level = 0`.

### 4. `authorType` enum on Post and Comment
- The spec says `author_id` can be User or Bot but gives no schema hint for how to distinguish them. Added `author_type` column (values: `USER`, `BOT`) as the discriminator. This lets the API route logic correctly without a union table or separate columns.

### 5. Guardrail execution order fix (correctness improvement)
- Naive implementation would check cooldown and set it optimistically, then check horizontal cap. If horizontal cap rejected, the cooldown would be set even though the comment was rejected — meaning the bot would be blocked from retrying for 10 minutes unfairly.
- Fixed: order is now (1) vertical check, (2) cooldown existence check (read-only), (3) horizontal cap increment (Lua), (4) cooldown set. Side-effect writes only happen after all checks pass.
  See `service/GuardrailService.java:checkAndReserveBotSlot`.

### 6. Redis Set for pending-notification tracking
- Sweeper needs to find all users with pending notifications. Naive approach: Redis `SCAN` with pattern `user:*:pending_notifs` — slow and blocks Redis.
- Extra: maintain `users:pending:notifs` Set (add member when queuing, remove after sweeping). Sweeper reads this Set in O(N users) with no SCAN.

### 7. `GlobalExceptionHandler`
- Maps `GuardrailException` → HTTP 429 and `IllegalArgumentException` → HTTP 400 with JSON error body. Not in spec but required for the Postman collection to show meaningful error responses.

### 8. Input author validation on post creation
- `createPost` verifies the `authorId` exists in the `users` or `bots` table before persisting. Prevents orphan posts pointing to non-existent authors.

---

## Redis Key Reference

| Key pattern | Type | TTL | Purpose |
|---|---|---|---|
| `post:{id}:virality_score` | String (integer) | none | Running virality score |
| `post:{id}:bot_count` | String (integer) | none | Bot reply counter (horizontal cap) |
| `cooldown:bot_{id}:human_{id}` | String | 10 min | Per-bot-per-human interaction cooldown |
| `notif:cooldown:{userId}` | String | 15 min | Per-user notification send cooldown |
| `user:{id}:pending_notifs` | List | none (cleared by sweeper) | Queued notification strings |
| `users:pending:notifs` | Set | none | Index of users who have pending notifications |

---

## How to Run

```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Build and run
mvn spring-boot:run

# 3. Import postman_collection.json into Postman
# 4. Create a user, create a bot, then test endpoints in order
```

Service runs on `http://localhost:8080`.
