//package org.example.springboot.config;
//
//import com.fasterxml.jackson.annotation.JsonTypeInfo;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
//import org.springframework.cache.CacheManager;
//import org.springframework.cache.annotation.EnableCaching;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Primary;
//import org.springframework.data.redis.cache.RedisCacheConfiguration;
//import org.springframework.data.redis.cache.RedisCacheManager;
//import org.springframework.data.redis.connection.RedisConnectionFactory;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
//import org.springframework.data.redis.serializer.RedisSerializationContext;
//import org.springframework.data.redis.serializer.StringRedisSerializer;
//
//import java.time.Duration;
//
//@Configuration
//@EnableCaching  // 开启 @Cacheable 注解支持
//public class RedisConfig {
//
//    /**
//     * 创建 ObjectMapper（带类型信息，方便反序列化）
//     */
//    private ObjectMapper createObjectMapper() {
//        ObjectMapper mapper = new ObjectMapper();
//        // 启用类型信息，这样反序列化时知道是什么类
//        mapper.activateDefaultTyping(
//                LaissezFaireSubTypeValidator.instance,
//                ObjectMapper.DefaultTyping.NON_FINAL,
//                JsonTypeInfo.As.PROPERTY
//        );
//        return mapper;
//    }
//
//
//    @Bean
//    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
//        RedisTemplate<String, Object> template = new RedisTemplate<>();
//        template.setConnectionFactory(factory);
//
//        // Key 用字符串
//        StringRedisSerializer stringSerializer = new StringRedisSerializer();
//        // Value 用 Jackson（带类型信息）
//        GenericJackson2JsonRedisSerializer jacksonSerializer =
//                new GenericJackson2JsonRedisSerializer(createObjectMapper());
//
//        template.setKeySerializer(stringSerializer);
//        template.setHashKeySerializer(stringSerializer);
//        template.setValueSerializer(jacksonSerializer);
//        template.setHashValueSerializer(jacksonSerializer);
//
//        template.afterPropertiesSet();
//        return template;
//    }
//
//    /**
//     * Spring Cache 管理器（注解方式）
//     */
//    @Bean
//    @Primary
//    public CacheManager cacheManager(RedisConnectionFactory factory) {
//        GenericJackson2JsonRedisSerializer jacksonSerializer =
//                new GenericJackson2JsonRedisSerializer(createObjectMapper());
//
//        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
//                .entryTtl(Duration.ofMinutes(10))  // 默认10分钟过期
//                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
//                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jacksonSerializer))
//                .disableCachingNullValues();  // 不缓存null
//
//        return RedisCacheManager.builder(factory)
//                .cacheDefaults(config)
//                .withCacheConfiguration("orders", RedisCacheConfiguration.defaultCacheConfig()
//                        .entryTtl(Duration.ofMinutes(10)))
//                .withCacheConfiguration("products", RedisCacheConfiguration.defaultCacheConfig()
//                        .entryTtl(Duration.ofHours(2)))
//                .transactionAware()
//                .build();
//    }
//}