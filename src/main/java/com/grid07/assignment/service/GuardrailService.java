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

    public void checkAndReserveBotSlot(Long postId, Long botId, Long postAuthorId, int depthLevel) {
        checkVerticalCap(depthLevel);
        checkCooldown(botId, postAuthorId);
        checkAndIncrementHorizontalCap(postId);
    }

    public void releaseBotSlot(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        redisTemplate.opsForValue().decrement(key);
    }

    private void checkVerticalCap(int depthLevel) {
        if (depthLevel > VERTICAL_CAP) {
            throw new GuardrailException(
                "Vertical cap exceeded: max depth is " + VERTICAL_CAP + " levels");
        }
    }

    private void checkCooldown(Long botId, Long humanId) {
        String key = String.format(COOLDOWN_KEY, botId, humanId);
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            throw new GuardrailException(
                "Cooldown active: bot " + botId + " cannot interact with user " + humanId +
                " more than once per " + COOLDOWN_MINUTES + " minutes");
        }
        redisTemplate.opsForValue().set(key, "1", Duration.ofMinutes(COOLDOWN_MINUTES));
    }

    /**
     * Atomically increments the bot reply counter only if it stays at or below HORIZONTAL_CAP.
     * Lua script runs as a single atomic Redis operation — closes the race window between
     * read and write that would otherwise allow > 100 concurrent bot comments.
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
