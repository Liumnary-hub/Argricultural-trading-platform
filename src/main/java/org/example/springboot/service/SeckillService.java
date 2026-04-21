package org.example.springboot.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.example.springboot.common.Result;
import org.example.springboot.config.SnowflakeIdWorker;
import org.example.springboot.mapper.SeckillProductMapper;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SeckillService {


    private static final DefaultRedisScript<Object> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Object.class);
    }
//    @Autowired
//    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired

    private SnowflakeIdWorker idWorker;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private SeckillProductMapper seckillProductMapper;



    public Result<?> seckill(Long userId, Long productId, Integer quantity) {
        // 1. 生成订单号
        Long orderId = idWorker.nextId();

        // 2. 执行 Lua 脚本
        List<String> keys = Arrays.asList("seckill:stock:" + productId, "seckill:users:" + productId);
        List<String> args = Arrays.asList(String.valueOf(productId), String.valueOf(userId),
                String.valueOf(quantity), String.valueOf(orderId));
        Object result = redisTemplate.execute(SECKILL_SCRIPT, keys, args.toArray());

        // 3. 处理返回结果
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            String status = (String) list.get(0);
            if ("err".equals(status)) {
                String errMsg = (String) list.get(1);
                return Result.error("-1", errMsg);
            } else if ("ok".equals(status)) {
                // 可选：检查剩余库存是否为0，异步更新数据库
                return Result.success("排队中，订单号：" + orderId);
            }
        }
        else {

                log.warn("Lua 脚本返回了意外的类型: {}, 值: {}", result.getClass(), result);
                return Result.success("排队中，订单号：" + orderId);

        }
        return Result.error("-1", "系统异常");
    }






    //依旧是抽取方法外围加上分布式锁
//    public Result<?> seckill(Long userId, Long productId, Integer quantity) {
//        String lockKey = "seckill:lock:" + productId + ":" + userId;
//        RLock lock = redissonClient.getLock(lockKey);
//        boolean locked = false;
//        try {
//            locked = lock.tryLock(3, 5, TimeUnit.SECONDS);
//            if (!locked) {
//                return Result.error("-1", "操作太频繁，请稍后再试");
//            }
//
//            // 2. 生成订单号
//
//
//            Long orderId = idWorker.nextId();
//            List<String> keys = Arrays.asList(
//                    "seckill:stock:" + productId,
//                    "seckill:users:" + productId
//            );
//            List<String> args = Arrays.asList(
//                    String.valueOf(productId),
//                    String.valueOf(userId),
//                    String.valueOf(quantity),
//                    String.valueOf(orderId)
//            );
//
//
////            log.info("开始执行 Lua 脚本，orderId={}", orderId);
//            Object result = redisTemplate.execute(SECKILL_SCRIPT, keys, args.toArray());
//            log.info("Lua 脚本执行完毕，result={}", result);
//
//            Long currentStock = redisTemplate.opsForValue().increment("seckill:stock:" + productId, 0);
//            log.info("当前 Redis 库存: {}", currentStock);
//
//            if (result instanceof List) {
//                List<?> list = (List<?>) result;
//                String status = (String) list.get(0);
//                if ("err".equals(status)) {
//                    String errMsg = (String) list.get(1);
//                    return Result.error("-1", errMsg);
//                } else if ("ok".equals(status)) {
//                    // 取出剩余库存（第三个元素）
//                    String remainingStr = (String) list.get(2);
//                    long remaining = Long.parseLong(remainingStr);
//                    log.info("订单创建成功，orderId={}, 剩余库存={}", orderId, remaining);
////
////                    if (remaining == 0) {
////                        asyncUpdateDbStockToZero(productId);
////                    }
////                    return Result.success("排队中，订单号：" + orderId);
//                }
//            } else
//            {
//                log.warn("Lua 脚本返回了意外的类型: {}, 值: {}", result.getClass(), result);
//                return Result.error("-1", "系统异常");
//            }
//
//
//            return Result.success("排队中，订单号：" + orderId);
//
//
//
//
//
//
//
//
//
//
////            //redis快速校验一人一单，库存，比数据库更快
////            LocalDateTime now = LocalDateTime.now();
////            String cacheKey = "seckill:product:" + productId;
////            RMap<String, Object> cache = redissonClient.getMap(cacheKey);
////            if (!cache.isEmpty()) {
////                LocalDateTime start = LocalDateTime.parse((String) cache.get("startTime"));
////                LocalDateTime end = LocalDateTime.parse((String) cache.get("endTime"));
////                Integer stock = (Integer) cache.get("stock");
////                // 快速校验时间、库存...
////                if (now.isBefore(start) || now.isAfter(end) || stock < quantity) {
////                    return Result.error("-1", "秒杀未开始/已结束/库存不足");
////                }
////                // 还可以用 Redis Set 检查一人一单
////                String usersKey = "seckill:users:" + productId;
////                if (redissonClient.getSet(usersKey).contains(userId.toString())) {
////                    return Result.error("-1", "每人限购一单");
////                }
////            }
////            //调用抽取的方法
////            return this.doseckillwithlock(userId, productId, quantity);
//        } catch (InterruptedException e)
//        {
//            Thread.currentThread().interrupt();
//            return Result.error("-1", "系统繁忙");
//        }
//
//        finally {
//            if (locked && lock.isHeldByCurrentThread()) {
//                lock.unlock(); //最终一定要释放锁
//            }
//        }
//    }
//    @Async  // 需要配置线程池，或者简单用新线程
//    public void asyncUpdateDbStockToZero(Long productId) {
//        // 使用分布式锁防止并发重复更新（可选）
//        String lockKey = "db:stock:update:" + productId;
//        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMinutes(1));
//        if (Boolean.FALSE.equals(locked)) {
//            log.info("已有其他线程在更新数据库库存，跳过");
//            return;
//        }
//        try {
//            // 再次检查 Redis 库存是否真的为 0（防止期间被恢复）
//            String stockKey = "seckill:stock:" + productId;
//            String currentStock = redisTemplate.opsForValue().get(stockKey);
//            if (currentStock == null || !"0".equals(currentStock)) {
//                log.info("Redis 库存不是 0，跳过数据库更新");
//                return;
//            }
//            // 更新数据库库存为 0
//            int updated = seckillProductMapper.updateStockToZero(productId);
//            if (updated > 0) {
//                log.info("数据库库存已更新为 0，productId={}", productId);
//            }
//        } finally {
//            redisTemplate.delete(lockKey);
//        }
//    }

}
