package org.example.springboot.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisStreamConfig {



    @Autowired
    private StringRedisTemplate redisTemplate;

    @PostConstruct
    public void initStream() {
        String streamKey = "stream:seckill_order";
        String group = "seckill_group";
        try {
            // 创建消费者组（如果不存在）
            redisTemplate.opsForStream().createGroup(streamKey, group);
        } catch (Exception e) {
            // 组已存在或 stream 不存在，忽略
        }
    }
}