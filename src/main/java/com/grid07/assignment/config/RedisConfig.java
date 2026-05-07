package com.grid07.assignment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Atomically increments a counter only if it stays at or below the cap.
     * Returns the new count, or -1 if the cap would be exceeded.
     * Single Lua script guarantees atomicity — no race window between GET and INCR.
     */
    @Bean
    public DefaultRedisScript<Long> atomicIncrIfBelowCapScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count > tonumber(ARGV[1]) then " +
            "    redis.call('DECR', KEYS[1]) " +
            "    return -1 " +
            "end " +
            "return count"
        );
        script.setResultType(Long.class);
        return script;
    }
}
