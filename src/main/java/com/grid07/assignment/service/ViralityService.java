package com.grid07.assignment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ViralityService {

    private static final Logger log = LoggerFactory.getLogger(ViralityService.class);
    private static final String VIRALITY_KEY = "post:%d:virality_score";

    private final RedisTemplate<String, String> redisTemplate;

    public ViralityService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void recordBotReply(Long postId) {
        increment(postId, 1);
    }

    public void recordHumanLike(Long postId) {
        increment(postId, 20);
    }

    public void recordHumanComment(Long postId) {
        increment(postId, 50);
    }

    public Long getViralityScore(Long postId) {
        String key = String.format(VIRALITY_KEY, postId);
        String val = redisTemplate.opsForValue().get(key);
        return val == null ? 0L : Long.parseLong(val);
    }

    private void increment(Long postId, long points) {
        String key = String.format(VIRALITY_KEY, postId);
        redisTemplate.opsForValue().increment(key, points);
        log.debug("Virality post:{} += {}", postId, points);
    }
}
