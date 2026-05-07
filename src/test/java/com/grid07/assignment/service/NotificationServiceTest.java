package com.grid07.assignment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ListOperations<String, String> listOps;
    @Mock SetOperations<String, String> setOps;

    NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(redisTemplate);
    }

    @Test
    void no_cooldown_logs_and_sets_cooldown_key() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey("notif:cooldown:1")).thenReturn(false);

        notificationService.handleBotInteractionNotification(1L, "BotX", "replied to your post");

        verify(valueOps).set(eq("notif:cooldown:1"), eq("1"), eq(Duration.ofMinutes(15)));
        verifyNoInteractions(listOps);
    }

    @Test
    void on_cooldown_queues_to_pending_list() {
        when(redisTemplate.hasKey("notif:cooldown:2")).thenReturn(true);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        notificationService.handleBotInteractionNotification(2L, "BotY", "replied to your post");

        verify(listOps).rightPush("user:2:pending_notifs", "Bot BotY replied to your post");
        verify(setOps).add("users:pending:notifs", "2");
        verifyNoInteractions(valueOps);
    }

    @Test
    void pending_notif_message_format_is_correct() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        notificationService.handleBotInteractionNotification(5L, "Alpha", "replied to your post");

        verify(listOps).rightPush("user:5:pending_notifs", "Bot Alpha replied to your post");
    }

    @Test
    void cooldown_key_uses_correct_format() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey("notif:cooldown:99")).thenReturn(false);

        notificationService.handleBotInteractionNotification(99L, "BotZ", "replied to your post");

        verify(valueOps).set(eq("notif:cooldown:99"), any(), any(Duration.class));
    }
}
