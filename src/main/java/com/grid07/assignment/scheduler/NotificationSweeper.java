package com.grid07.assignment.scheduler;

import com.grid07.assignment.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class NotificationSweeper {

    private static final Logger log = LoggerFactory.getLogger(NotificationSweeper.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final NotificationService notificationService;

    public NotificationSweeper(RedisTemplate<String, String> redisTemplate,
                                NotificationService notificationService) {
        this.redisTemplate       = redisTemplate;
        this.notificationService = notificationService;
    }

    /**
     * Phase 3 — CRON Sweeper.
     * Runs every 5 minutes (simulating the 15-min production sweep per spec).
     * Pops all pending notifications per user, logs exactly:
     *   "Summarized Push Notification: Bot X and [N] others interacted with your posts."
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void sweepPendingNotifications() {
        String pendingUsersSetKey = notificationService.getUsersPendingSetKey();
        Set<String> userIds = redisTemplate.opsForSet().members(pendingUsersSetKey);

        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        log.info("Notification sweeper: processing {} user(s) with pending notifications", userIds.size());

        for (String userIdStr : userIds) {
            Long userId;
            try {
                userId = Long.parseLong(userIdStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid userId in pending set: {}", userIdStr);
                redisTemplate.opsForSet().remove(pendingUsersSetKey, userIdStr);
                continue;
            }

            String pendingKey = notificationService.getPendingNotifsKey(userId);
            List<String> messages = redisTemplate.opsForList().range(pendingKey, 0, -1);

            if (messages == null || messages.isEmpty()) {
                redisTemplate.opsForSet().remove(pendingUsersSetKey, userIdStr);
                continue;
            }

            // messages[0] = "Bot X replied to your post" — extract "Bot X" as the lead name
            String firstMsg   = messages.get(0);
            String leadBotRef = extractBotRef(firstMsg);
            int    othersCount = messages.size() - 1;

            if (othersCount > 0) {
                log.info("Summarized Push Notification: {} and [{}] others interacted with your posts.",
                        leadBotRef, othersCount);
            } else {
                log.info("Summarized Push Notification: {} interacted with your posts.", leadBotRef);
            }

            // Atomically clear list then remove from tracking set
            redisTemplate.delete(pendingKey);
            redisTemplate.opsForSet().remove(pendingUsersSetKey, userIdStr);
        }
    }

    /**
     * Extracts "Bot X" from a stored notification string like "Bot X replied to your post".
     * Falls back to the full message if the format is unexpected.
     */
    private String extractBotRef(String message) {
        if (message == null) return "Bot";
        // message format: "Bot {name} {action...}"
        String[] parts = message.split(" ", 3);
        if (parts.length >= 2) {
            return parts[0] + " " + parts[1]; // "Bot {name}"
        }
        return message;
    }
}
