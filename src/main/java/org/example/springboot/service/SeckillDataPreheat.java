package org.example.springboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.springboot.entity.SeckillProduct;
import org.example.springboot.mapper.SeckillProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class SeckillDataPreheat implements CommandLineRunner {

    @Autowired
    private SeckillProductMapper seckillProductMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void run(String... args) throws Exception {
        List<SeckillProduct> list = seckillProductMapper.selectList(
                new LambdaQueryWrapper<SeckillProduct>().gt(SeckillProduct::getStock, 0)
        );
        for (SeckillProduct sp : list) {
            Long productId = sp.getProductId();
            String stockKey = "seckill:stock:" + productId;
            String usersKey = "seckill:users:" + productId;

            // 设置库存
            redisTemplate.opsForValue().set(stockKey, String.valueOf(sp.getStock()));
            // 清空用户集合，避免之前的测试数据影响新活动
            redisTemplate.delete(usersKey);

            log.info("预热商品库存: productId={}, stock={}", productId, sp.getStock());
        }
    }
}