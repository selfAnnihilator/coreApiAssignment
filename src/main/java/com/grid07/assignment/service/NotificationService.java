package com.grid07.assignment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String NOTIF_COOLDOWN_KEY = "notif:cooldown:%d";
    private static final String PENDING_NOTIFS_KEY = "user:%d:pending_notifs";
    private static final String USERS_PENDING_SET  = "users:pending:notifs";
    private static final long   NOTIF_COOLDOWN_MIN = 15;

    private final RedisTemplate<String, String> redisTemplate;

    public NotificationService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Phase 3 — Redis Throttler.
     * Stores just the botName in the pending list so the sweeper can produce
     * the exact summary format: "Bot X and [N] others interacted with your posts."
     */
    public void handleBotInteractionNotification(Long userId, String botName, String action) {
        String cooldownKey = String.format(NOTIF_COOLDOWN_KEY, userId);

        Boolean onCooldown = redisTemplate.hasKey(cooldownKey);
        if (Boolean.TRUE.equals(onCooldown)) {
            // Queue the full notification string per spec: "Bot X replied to your post"
            String pendingKey = String.format(PENDING_NOTIFS_KEY, userId);
            String notifMessage = "Bot " + botName + " " + action;
            redisTemplate.opsForList().rightPush(pendingKey, notifMessage);
            // Track user in Set so sweeper finds them without SCAN
            redisTemplate.opsForSet().add(USERS_PENDING_SET, String.valueOf(userId));
            log.debug("Queued pending notification for user {}: {}", userId, notifMessage);
        } else {
            log.info("Push Notification Sent to User {}: Bot {} {}", userId, botName, action);
            redisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofMinutes(NOTIF_COOLDOWN_MIN));
        }
    }

    public String getUsersPendingSetKey() {
        return USERS_PENDING_SET;
    }

    public String getPendingNotifsKey(Long userId) {
        return String.format(PENDING_NOTIFS_KEY, userId);
    }
}
