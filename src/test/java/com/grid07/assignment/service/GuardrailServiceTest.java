package com.grid07.assignment.service;

import com.grid07.assignment.exception.GuardrailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardrailServiceTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock DefaultRedisScript<Long> atomicIncrIfBelowCapScript;
    @Mock ValueOperations<String, String> valueOps;

    GuardrailService guardrailService;

    @BeforeEach
    void setUp() {
        guardrailService = new GuardrailService(redisTemplate, atomicIncrIfBelowCapScript);
    }

    // ── Vertical Cap ──────────────────────────────────────────────────────────

    @Test
    void verticalCap_passes_at_exactly_20() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(1L);

        assertThatNoException().isThrownBy(
            () -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 20));
    }

    @Test
    void verticalCap_rejects_at_21() {
        assertThatThrownBy(() -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 21))
            .isInstanceOf(GuardrailException.class)
            .hasMessageContaining("Vertical cap");
    }

    @Test
    void verticalCap_rejects_at_0_level_post_with_depth_100() {
        assertThatThrownBy(() -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 100))
            .isInstanceOf(GuardrailException.class);
    }

    // ── Cooldown Cap ─────────────────────────────────────────────────────────

    @Test
    void cooldown_passes_when_key_absent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(1L);

        assertThatNoException().isThrownBy(
            () -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 0));
    }

    @Test
    void cooldown_rejects_when_key_exists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 0))
            .isInstanceOf(GuardrailException.class)
            .hasMessageContaining("Cooldown active");
    }

    @Test
    void cooldown_key_uses_correct_format() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("cooldown:bot_7:human_3"), anyString(), any(Duration.class)))
            .thenReturn(false);

        assertThatThrownBy(() -> guardrailService.checkAndReserveBotSlot(1L, 7L, 3L, 0))
            .isInstanceOf(GuardrailException.class);
    }

    @Test
    void cooldown_rolled_back_when_horizontal_cap_rejects() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(-1L);

        assertThatThrownBy(() -> guardrailService.checkAndReserveBotSlot(1L, 5L, 2L, 0))
            .isInstanceOf(GuardrailException.class)
            .hasMessageContaining("Horizontal cap");

        verify(redisTemplate).delete("cooldown:bot_5:human_2");
    }

    // ── Horizontal Cap ────────────────────────────────────────────────────────

    @Test
    void horizontalCap_passes_when_lua_returns_valid_count() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(50L);

        assertThatNoException().isThrownBy(
            () -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 0));
    }

    @Test
    void horizontalCap_passes_at_exactly_100() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(100L);

        assertThatNoException().isThrownBy(
            () -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 0));
    }

    @Test
    void horizontalCap_rejects_when_lua_returns_minus_one() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(-1L);

        assertThatThrownBy(() -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 0))
            .isInstanceOf(GuardrailException.class)
            .hasMessageContaining("Horizontal cap");
    }

    @Test
    void horizontalCap_rejects_when_lua_returns_null() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(null);

        assertThatThrownBy(() -> guardrailService.checkAndReserveBotSlot(1L, 1L, 1L, 0))
            .isInstanceOf(GuardrailException.class);
    }

    @Test
    void horizontalCap_uses_correct_redis_key() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(), eq(List.of("post:42:bot_count")), any()))
            .thenReturn(1L);

        assertThatNoException().isThrownBy(
            () -> guardrailService.checkAndReserveBotSlot(42L, 1L, 1L, 0));
    }

    // ── Release ───────────────────────────────────────────────────────────────

    @Test
    void releaseBotSlot_decrements_counter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        guardrailService.releaseBotSlot(10L);
        verify(valueOps).decrement("post:10:bot_count");
    }

    @Test
    void releaseCooldown_deletes_key() {
        guardrailService.releaseCooldown(3L, 7L);
        verify(redisTemplate).delete("cooldown:bot_3:human_7");
    }
}
