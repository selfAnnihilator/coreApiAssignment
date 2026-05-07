package com.grid07.assignment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ViralityServiceTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    ViralityService viralityService;

    @BeforeEach
    void setUp() {
        viralityService = new ViralityService(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void recordBotReply_increments_by_1() {
        viralityService.recordBotReply(1L);
        verify(valueOps).increment("post:1:virality_score", 1L);
    }

    @Test
    void recordHumanLike_increments_by_20() {
        viralityService.recordHumanLike(5L);
        verify(valueOps).increment("post:5:virality_score", 20L);
    }

    @Test
    void recordHumanComment_increments_by_50() {
        viralityService.recordHumanComment(9L);
        verify(valueOps).increment("post:9:virality_score", 50L);
    }

    @Test
    void getViralityScore_returns_0_when_key_absent() {
        when(valueOps.get("post:1:virality_score")).thenReturn(null);
        assertThat(viralityService.getViralityScore(1L)).isEqualTo(0L);
    }

    @Test
    void getViralityScore_returns_parsed_value() {
        when(valueOps.get("post:3:virality_score")).thenReturn("71");
        assertThat(viralityService.getViralityScore(3L)).isEqualTo(71L);
    }

    @Test
    void virality_key_format_is_correct() {
        viralityService.recordBotReply(42L);
        verify(valueOps).increment("post:42:virality_score", 1L);
    }
}
