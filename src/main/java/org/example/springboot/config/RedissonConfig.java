package org.example.springboot.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password:}") String password) {  // 默认空

        Config config = new Config();

        // 判断密码是否为空
        String redisPassword = password.trim().isEmpty() ? null : password;

        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(redisPassword)  // null 表示无密码，"" 会报错
                .setDatabase(0)
                .setConnectionMinimumIdleSize(10)
                .setConnectionPoolSize(64);

        return Redisson.create(config);
    }
}