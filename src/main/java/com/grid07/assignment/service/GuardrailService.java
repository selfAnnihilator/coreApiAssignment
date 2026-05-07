package com.grid07.assignment.service;

import com.grid07.assignment.exception.GuardrailException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailService.class);
    private static final String BOT_COUNT_KEY   = "post:%d:bot_count";
    private static final String COOLDOWN_KEY     = "cooldown:bot_%d:human_%d";
    private static final int    HORIZONTAL_CAP   = 100;
    private static final int    VERTICAL_CAP     = 20;
    private static final long   COOLDOWN_MINUTES = 10;

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> atomicIncrIfBelowCapScript;

    public GuardrailService(RedisTemplate<String, String> redisTemplate,
                            DefaultRedisScript<Long> atomicIncrIfBelowCapScript) {
        this.redisTemplate = redisTemplate;
        this.atomicIncrIfBelowCapScript = atomicIncrIfBelowCapScript;
    }

    /**
     * All three guardrail checks run atomically before any DB write.
     * 1. Vertical cap   — pure check, no Redis write
     * 2. Cooldown cap   — atomic SET NX EX (set-if-absent); throws if key already exists
     * 3. Horizontal cap — atomic INCR via Lua; if exceeded, cooldown key is rolled back
     *
     * Cooldown uses SET NX so the check+set is one indivisible Redis operation —
     * no two concurrent threads can both see "no cooldown" and both succeed.
     */
    public void checkAndReserveBotSlot(Long postId, Long botId, Long postAuthorId, int depthLevel) {
        checkVerticalCap(depthLevel);
        trySetCooldown(botId, postAuthorId);           // atomic SET NX EX — throws if exists
        try {
            checkAndIncrementHorizontalCap(postId);    // Lua INCR — may throw 429
        } catch (GuardrailException e) {
            releaseCooldown(botId, postAuthorId);      // rollback cooldown if horizontal cap rejects
            throw e;
        }
    }

    /**
     * Release the reserved horizontal cap slot if the DB write fails.
     * Keeps Redis and Postgres consistent on error paths.
     */
    public void releaseBotSlot(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        redisTemplate.opsForValue().decrement(key);
    }

    public void releaseCooldown(Long botId, Long humanId) {
        redisTemplate.delete(String.format(COOLDOWN_KEY, botId, humanId));
    }

    private void checkVerticalCap(int depthLevel) {
        if (depthLevel > VERTICAL_CAP) {
            throw new GuardrailException(
                "Vertical cap exceeded: max depth is " + VERTICAL_CAP + " levels");
        }
    }

    /**
     * Atomically sets the cooldown key only if it does not already exist (SET NX EX).
     * Returns true and sets TTL if the key was absent (slot acquired).
     * Throws GuardrailException if the key already existed (cooldown active).
     * This collapses EXISTS + SET into one Redis round-trip with no race window.
     */
    private void trySetCooldown(Long botId, Long humanId) {
        String key = String.format(COOLDOWN_KEY, botId, humanId);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofMinutes(COOLDOWN_MINUTES));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new GuardrailException(
                "Cooldown active: bot " + botId + " cannot interact with user " + humanId +
                " more than once per " + COOLDOWN_MINUTES + " minutes");
        }
    }

    /**
     * Atomically increments the bot reply counter only if it stays at or below HORIZONTAL_CAP.
     * The Lua script collapses read-check-write into one indivisible Redis operation,
     * preventing the race where 200 concurrent threads all read "99" and all increment.
     */
    private void checkAndIncrementHorizontalCap(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        Long result = redisTemplate.execute(
            atomicIncrIfBelowCapScript,
            List.of(key),
            String.valueOf(HORIZONTAL_CAP)
        );
        if (result == null || result == -1L) {
            throw new GuardrailException(
                "Horizontal cap exceeded: post " + postId + " has reached the " +
                HORIZONTAL_CAP + " bot reply limit");
        }
    }
}
