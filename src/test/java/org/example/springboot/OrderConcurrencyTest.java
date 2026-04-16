package org.example.springboot;

import org.example.springboot.common.Result;
import org.example.springboot.entity.Order;
import org.example.springboot.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@ActiveProfiles("test")  // 激活测试配置
public class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;

    // 并发线程数（模拟 100 个用户同时下单）
    private static final int THREAD_COUNT = 100;

    // 每个用户购买数量
    private static final int QUANTITY_PER_ORDER = 1;

    // 线程池
    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

    // 成功计数器
    private final AtomicInteger successCount = new AtomicInteger(0);
    // 失败计数器
    private final AtomicInteger failCount = new AtomicInteger(0);

    @Test
    public void testConcurrentCreateOrder() throws InterruptedException {
        // 商品ID（确保测试数据库中存在该商品）
        Long productId = 1L;

        // 用于等待所有线程完成
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // 记录开始时间
        long startTime = System.currentTimeMillis();

        // 启动多个线程并发调用 createOrder
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    // 构造订单对象
                    Order order = new Order();
                    order.setProductId(productId);
                    order.setQuantity(QUANTITY_PER_ORDER);
                    order.setUserId(8L);
                    // 注意：实际业务中单价应从商品表获取，此处为简化测试，使用固定值
                    order.setPrice(new BigDecimal("10.00"));
                    // 其他必填字段根据你的实体类补充，例如：
                    // order.setUserId(1L);
                    // order.setAddress("测试地址");

                    // 调用业务方法
                    Result<?> result = orderService.createOrder(order);

                    if (result.getCode().equals("0")) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        System.err.println("下单失败: " + result.getMsg());
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("异常: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有线程执行完毕
        latch.await();
        long endTime = System.currentTimeMillis();

        // 关闭线程池
        executor.shutdown();

        // 输出统计结果
        System.out.println("========== 并发测试结果 ==========");
        System.out.println("并发数: " + THREAD_COUNT);
        System.out.println("成功数: " + successCount.get());
        System.out.println("失败数: " + failCount.get());
        System.out.println("耗时: " + (endTime - startTime) + " ms");
        System.out.println("=================================");

        // 可以在此添加断言来验证数据一致性
        // 例如：查询商品剩余库存，判断是否 = 初始库存 - 成功数 * 购买数量
    }
}