package org.example.springboot.service;



import lombok.extern.slf4j.Slf4j;
import org.example.springboot.entity.OrderTimeoutTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class SeckillOrderConsumer implements CommandLineRunner {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private SeckillOrderService seckillOrderService;

    private static final String STREAM_KEY = "stream:seckill_order";
    private static final String GROUP = "seckill_group";
    private static final String CONSUMER_NAME = "consumer_" + UUID.randomUUID();


    @Override
    public void run(String... args) throws Exception {


        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            log.info("消费者组已存在或创建失败: {}", e.getMessage());
            log.error("创建消费者组失败，详细错误：", e);
        }




        Thread consumerThread = new Thread(this::pollMessages, "seckill-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }


    private void pollMessages() {
        log.info("消费者线程 {} 开始轮询消息", CONSUMER_NAME);
        while (true) {
            try {

//                log.info("准备从 Stream 读取消息...");
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
//                log.info("读取到 {} 条消息", records == null ? 0 : records.size());

                for (MapRecord<String, Object, Object> record : records) {
                    Map<Object, Object> value = record.getValue();
                    Long orderId = Long.valueOf((String) value.get("orderId"));
                    Long userId = Long.valueOf((String) value.get("userId"));
                    Long productId = Long.valueOf((String) value.get("productId"));
                    Integer quantity = Integer.valueOf((String) value.get("quantity"));

                    try {
                        // 调用落库服务
                        seckillOrderService.createOrderAsync(orderId, userId, productId, quantity);
                        // 处理成功，ACK
                        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                        log.info("订单落库成功，orderId={}", orderId);
                        // 投递延迟取消任务
                        scheduleOrderCancel(orderId, productId, userId);
                    } catch (DuplicateKeyException e) {
                        // 幂等：订单已存在，视为成功，ACK
                        log.warn("订单已存在，幂等处理，orderId={}", orderId);
                        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                    } catch (Exception e) {
                        log.error("订单落库失败，orderId={}", orderId, e);
                        // 不 ACK，等待下次重试
                    }
                }
            } catch (Exception e) {
                log.error("消费消息异常", e);
                // 可在此处理 Pending 消息，简化版略
            }
        }
    }

    private void scheduleOrderCancel(Long orderId, Long productId, Long userId) {
        RBlockingDeque<OrderTimeoutTask> blockingDeque = redissonClient.getBlockingDeque("order:cancel:queue");
        RDelayedQueue<OrderTimeoutTask> delayedQueue = redissonClient.getDelayedQueue(blockingDeque);
        OrderTimeoutTask task = new OrderTimeoutTask(orderId, productId, userId);
        delayedQueue.offer(task, 30, TimeUnit.MINUTES);  // 30分钟超时
    }

}
