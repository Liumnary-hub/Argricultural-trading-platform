package org.example.springboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.example.springboot.common.Result;
import org.example.springboot.config.ThreadPoolConfig;
import org.example.springboot.entity.*;
import org.example.springboot.mapper.*;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private RedissonClient redissonClient;


    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private LogisticsMapper logisticsMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private SeckillProductMapper seckillProductMapper;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Async("taskExecutor")
    public void asyncAfterPaySuccess(Order order, Product product) {
        try {
            System.out.println("异步执行支付后业务，线程：" + Thread.currentThread().getName());



        } catch (Exception e) {
            // 异步异常不影响主支付流程
            e.printStackTrace();
        }
    }



    @Transactional  // 事务：订单和库存要么一起成功，要么一起失败
    public Result<?> createOrder(Order order) {
        try {
            // 1. 查询商品 + 加行锁
            Product product = productMapper.selectOne(
                    new LambdaQueryWrapper<Product>()
                            .eq(Product::getId, order.getProductId())
                            .last("FOR UPDATE")
            );

            // 2. 商品不存在
            if (product == null) {
                return Result.error("-1", "商品不存在");
            }

            // 3. 库存判断
            if (product.getStock() < order.getQuantity()) {
                return Result.error("-1", "库存不足，无法下单");
            }

            // 4. 计算总价
            order.setTotalPrice(order.getPrice().multiply(BigDecimal.valueOf(order.getQuantity())));

            // 5. 订单状态默认 0 = 未支付
            order.setStatus(0);

            // 6. 插入订单
            int result = orderMapper.insert(order);
            if (result <= 0) {
                return Result.error("-1", "创建订单失败");
            }

            // ------------------------------
            // 7. 扣库存（下单就锁定库存，防止别人抢走）
            // ------------------------------
            product.setStock(product.getStock() - order.getQuantity());
            productMapper.updateById(product);

            LOGGER.info("创建订单成功，订单ID：{}，已锁定库存", order.getId());
            return Result.success(order);

        } catch (Exception e) {
            LOGGER.error("创建订单异常：{}", e.getMessage());
            throw new RuntimeException(e.getMessage(), e); // 重新抛出，触发回滚,要么去掉trycath，要么主动回滚，要么抛出运行时异常
        }
    }






    // 拆出来的业务方法（单独加事务）
    @Transactional
    protected Result<?> doPayWithLock(Long id) {
        // 4. 【数据库层】悲观锁（防数据不一致）
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getId, id)
                        .last("FOR UPDATE")
        );

        if (order == null) {
            return Result.error("-1", "订单不存在");
        }

        // 5. 双重检查（防止重复支付）
        if (order.getStatus() == 1) {
            return Result.error("-1", "订单已支付");
        }

        // 6. 查商品并加锁（同样逻辑）
        Product product = productMapper.selectOne(
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getId, order.getProductId())
                        .last("FOR UPDATE")
        );

        if (product == null || product.getStock() < order.getQuantity()) {
            return Result.error("-1", "库存不足");
        }

        // 7. 扣库存
        product.setStock(product.getStock() - order.getQuantity());
        product.setSalesCount(product.getSalesCount() + order.getQuantity());
        productMapper.updateById(product);

        // 8. 改订单状态
        order.setStatus(1);
        orderMapper.updateById(order);

        // 9. 标记Redis缓存（可选，用于快速查询支付状态）
        // redisTemplate.opsForValue().set("pay:status:" + id, "SUCCESS", 24, TimeUnit.HOURS);

        return Result.success();
    }

    public Result<?> updateOrderStatus(Long id, Integer status) {
        try {
            Order order = orderMapper.selectById(id);
            if (order == null) {
                return Result.error("-1", "未找到订单");
            }

            order.setLastStatus(order.getStatus());
            order.setStatus(status);
            int result = orderMapper.updateById(order);
            if (result > 0) {
                // 查找该订单的物流信息
                LambdaQueryWrapper<Logistics> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(Logistics::getOrderId, id);
                Logistics logistics = logisticsMapper.selectOne(queryWrapper);
                
                if (logistics != null) {
                    // 如果订单状态变为已退款，更新物流状态为已取消
                    if (status == 6) { // 6表示已退款
                        logistics.setStatus(3); // 3表示已取消
                        logisticsMapper.updateById(logistics);
                        LOGGER.info("订单退款成功，同步更新物流状态为已取消，物流ID：{}", logistics.getId());
                    }
                    // 如果订单状态变为已完成，更新物流状态为已签收
                    else if (status == 3) { // 3表示已完成
                        logistics.setStatus(2); // 2表示已签收
                        logisticsMapper.updateById(logistics);
                        LOGGER.info("订单已完成，同步更新物流状态为已签收，物流ID：{}", logistics.getId());
                    }
                }

                LOGGER.info("更新订单状态成功，订单ID：{}，新状态：{}", id, status);
                return Result.success(order);
            }
            return Result.error("-1", "更新订单状态失败");
        } catch (Exception e) {
            LOGGER.error("更新订单状态失败：{}", e.getMessage());
            return Result.error("-1", "更新订单状态失败：" + e.getMessage());
        }
    }

    public Result<?> deleteOrder(Long id) {
        try {
            deleteRelation(id);
            int result = orderMapper.deleteById(id);

            if (result > 0) {
                LOGGER.info("删除订单成功，订单ID：{}", id);
                return Result.success();
            }
            return Result.error("-1", "删除订单失败");
        } catch (Exception e) {
            LOGGER.error("删除订单失败：{}", e.getMessage());
            return Result.error("-1", "删除订单失败：" + e.getMessage());
        }
    }

    public void deleteRelation(Long id){
        logisticsMapper.delete(new LambdaQueryWrapper<Logistics>().eq(Logistics::getOrderId,id));
    }

    public Result<?> getOrderById(Long id) {
        Order order = orderMapper.selectById(id);
        if (order != null) {
            // 填充关联信息
            order.setUser(userMapper.selectById(order.getUserId()));
            order.setProduct(productMapper.selectById(order.getProductId()));

            return Result.success(order);
        }
        return Result.error("-1", "未找到订单");
    }

    public Result<?> getOrdersByUserId(Long userId) {
        LambdaQueryWrapper<Order> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Order::getUserId, userId);
        queryWrapper.orderByDesc(Order::getCreatedAt);
        List<Order> orders = orderMapper.selectList(queryWrapper);
        if (orders != null && !orders.isEmpty()) {
            // 填充关联信息
            orders.forEach(order -> {
                order.setUser(userMapper.selectById(order.getUserId()));
                order.setProduct(productMapper.selectById(order.getProductId()));
            });
            return Result.success(orders);
        }
        return Result.error("-1", "未找到订单");
    }

    public Result<?> getOrdersByPage(Long userId,Long id,String status, Long merchantId,Integer currentPage, Integer size) {
        LambdaQueryWrapper<Order> queryWrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            queryWrapper.eq(Order::getUserId, userId);
        }
        if (id != null) {
            queryWrapper.eq(Order::getId, id);
        }
        if(StringUtils.isNotBlank(status)){
            queryWrapper.eq(Order::getStatus,status);
        }
        if (merchantId != null) {
            List<Product> product = productMapper.selectList(new LambdaQueryWrapper<Product>().eq(Product::getMerchantId, merchantId));

            if(!product.isEmpty()){
            List<Long> productIds = product.stream().map(Product::getId).collect(Collectors.toList());
            queryWrapper.in(Order::getProductId, productIds);
            }else{
                Page<Order> page = new Page<>(currentPage, size);
                page.setTotal(0);
                page.setRecords(null);
                return Result.success(page);
            }
        }

        queryWrapper.orderByDesc(Order::getCreatedAt);

        Page<Order> page = new Page<>(currentPage, size);
        Page<Order> result = orderMapper.selectPage(page, queryWrapper);

        // 填充关联信息
        result.getRecords().forEach(order -> {
            order.setUser(userMapper.selectById(order.getUserId()));
            Product product = productMapper.selectById(order.getProductId());
            if(product!=null){
                order.setProduct(product);
                order.setMerchant(userMapper.selectById(product.getMerchantId()));
            }else{
                order.setProduct(null);
                order.setMerchant(null);
            }
        });

        return Result.success(result);
    }

    public Result<?> refundOrder(Long id, String reason) {
        try {
            Order order = orderMapper.selectById(id);
            if (order == null) {
                return Result.error("-1", "未找到订单");
            }

            // 检查订单状态是否允许退款
            if (order.getStatus() != 1 && order.getStatus() != 2) {
                return Result.error("-1", "当前订单状态不允许退款");
            }

            order.setLastStatus(order.getStatus());  // 保存当前状态
            order.setStatus(5);  // 设为退款中
            order.setRefundStatus(1); // 申请退款
            order.setRefundReason(reason);
            int result = orderMapper.updateById(order);
            if (result > 0) {
                LOGGER.info("申请退款成功，订单ID：{}", id);
                return Result.success(order);
            }
            return Result.error("-1", "申请退款失败");
        } catch (Exception e) {
            LOGGER.error("申请退款失败：{}", e.getMessage());
            return Result.error("-1", "申请退款失败：" + e.getMessage());
        }
    }

    public Result<?> deleteBatch(List<Long> ids) {
        try {
            // 检查每个订单是否存在关联记录
            for (Long id : ids) {
                // 检查物流
                deleteRelation(id);
            }

            int result = orderMapper.deleteBatchIds(ids);
            if (result > 0) {
                LOGGER.info("批量删除订单成功，删除数量：{}", result);
                return Result.success();
            }
            return Result.error("-1", "批量删除订单失败");
        } catch (Exception e) {
            LOGGER.error("批量删除订单失败：{}", e.getMessage());
            return Result.error("-1", "批量删除订单失败：" + e.getMessage());
        }
    }



    public Result<?> payOrder(Long id) {
        // 1. 【外层】获取分布式锁（防并发、防重复点击）
        String lockKey = "pay:order:" + id;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试加锁：最多等3秒，锁30秒后自动释放（看门狗会续期）
            boolean locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
            if (!locked) {
                return Result.error("-1", "支付处理中，请勿重复点击");
            }

            // 2. 【内层】执行业务（带事务）
            return doPayWithLock(id);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("-1", "支付中断，请重试");
        } finally {
            // 3. 释放分布式锁（只有当前线程持有才释放）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public Result<?> updateOrderAddress(String name, Long id,String address, String phone) {
        try {
            Order order = orderMapper.selectById(id);
            if (order == null) {
                return Result.error("-1", "未找到订单");
            }

            // 检查订单状态，只有未发货的订单才能修改地址
            if (order.getStatus() > 1) {
                return Result.error("-1", "订单已发货，无法修改收货地址");
            }
            order.setRecvName(name);
            order.setRecvAddress(address);
            order.setRecvPhone(phone);
            
            int result = orderMapper.updateById(order);
            if (result > 0) {
                LOGGER.info("更新订单收货信息成功，订单ID：{}", id);
                return Result.success(order);
            }
            return Result.error("-1", "更新订单收货信息失败");
        } catch (Exception e) {
            LOGGER.error("更新订单收货信息失败：{}", e.getMessage());
            return Result.error("-1", "更新订单收货信息失败：" + e.getMessage());
        }
    }
    public Result<?> updateOrder(Long id, Order order) {
        try {
            Order existingOrder = orderMapper.selectById(id);
            if (existingOrder == null) {
                return Result.error("-1", "未找到订单");
            }

            // 设置ID确保更新正确的订单
            order.setId(id);
            
            // 保持原有的不可修改字段
            order.setCreatedAt(existingOrder.getCreatedAt());
            order.setUserId(existingOrder.getUserId());
            order.setProductId(existingOrder.getProductId());
            order.setTotalPrice(existingOrder.getTotalPrice());
            
            int result = orderMapper.updateById(order);
            if (result > 0) {
                // 查找该订单的物流信息
                LambdaQueryWrapper<Logistics> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(Logistics::getOrderId, id);
                Logistics logistics = logisticsMapper.selectOne(queryWrapper);
                
                if (logistics != null) {
                    // 如果订单状态变为已退款，更新物流状态为已取消
                    if (order.getStatus() == 6 && existingOrder.getStatus() != 6) {
                        logistics.setStatus(3); // 3表示已取消
                        logisticsMapper.updateById(logistics);
                        LOGGER.info("订单退款成功，同步更新物流状态为已取消，物流ID：{}", logistics.getId());
                    }
                    // 如果订单状态变为已完成，更新物流状态为已签收
                    else if (order.getStatus() == 3 && existingOrder.getStatus() != 3) {
                        logistics.setStatus(2); // 2表示已签收
                        logisticsMapper.updateById(logistics);
                        LOGGER.info("订单已完成，同步更新物流状态为已签收，物流ID：{}", logistics.getId());
                    }
                }

                LOGGER.info("更新订单成功，订单ID：{}", id);
                return Result.success(order);
            }
            return Result.error("-1", "更新订单信息失败");
        } catch (Exception e) {
            LOGGER.error("更新订单失败：{}", e.getMessage());
            return Result.error("-1", "更新订单失败：" + e.getMessage());
        }
    }

    public Result<?> getOrderLogistics(Long orderId) {
        try {
            // 检查订单是否存在
            Order order = orderMapper.selectById(orderId);
            if (order == null) {
                return Result.error("-1", "未找到订单");
            }

            // 查询物流信息
            LambdaQueryWrapper<Logistics> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Logistics::getOrderId, orderId);
            Logistics logistics = logisticsMapper.selectOne(queryWrapper);
            
            if (logistics != null) {
                // 填充关联信息
                logistics.setOrder(order);

                return Result.success(logistics);
            }
            return Result.error("-1", "未找到物流信息");
        } catch (Exception e) {
            LOGGER.error("查询订单物流信息失败：{}", e.getMessage());
            return Result.error("-1", "查询订单物流信息失败：" + e.getMessage());
        }
    }

    /**
     * 处理退款申请
     * @param id 订单ID
     * @param status 退款状态：6-同意退款 7-拒绝退款
     * @param remark 处理备注
     * @return 处理结果
     */
    public Result<?> handleRefund(Long id, Integer status, String remark) {
        try {
            Order order = orderMapper.selectById(id);
            if (order == null) {
                return Result.error("-1", "未找到订单");
            }

            // 检查订单是否处于退款中状态
            if (order.getStatus() != 5) {
                return Result.error("-1", "订单当前状态不是退款中");
            }

            // 保存原始状态
            order.setLastStatus(order.getStatus());
            // 更新状态
            order.setStatus(status);
            order.setRefundStatus(status == 6 ? 3 : 4); // 3-已退款 4-退款失败
            order.setRefundTime(Timestamp.valueOf(LocalDateTime.now()));
            order.setRemark(remark);
            
            int result = orderMapper.updateById(order);
            if (result > 0) {
                // 如果同意退款，恢复商品库存
                if (status == 6) {
                    Product product = productMapper.selectById(order.getProductId());
                    if (product != null) {
                        // 增加库存
                        product.setStock(product.getStock() + order.getQuantity());
                        // 减少销量
                        if (product.getSalesCount() >= order.getQuantity()) {
                            product.setSalesCount(product.getSalesCount() - order.getQuantity());
                        }
                        productMapper.updateById(product);
                        LOGGER.info("退款成功，已恢复商品库存，商品ID：{}，数量：{}", product.getId(), order.getQuantity());
                    }
                }

                // 同步更新物流状态
                LambdaQueryWrapper<Logistics> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(Logistics::getOrderId, id);
                Logistics logistics = logisticsMapper.selectOne(queryWrapper);
                if (logistics != null && status == 6) { // 如果同意退款
                    logistics.setStatus(3); // 设置物流状态为已取消
                    logisticsMapper.updateById(logistics);
                    LOGGER.info("订单退款成功，同步更新物流状态为已取消，物流ID：{}", logistics.getId());
                }
                
                LOGGER.info("处理退款成功，订单ID：{}，处理结果：{}", id, status == 6 ? "已退款" : "拒绝退款");
                return Result.success(order);
            }
            return Result.error("-1", "处理退款失败");
        } catch (Exception e) {
            LOGGER.error("处理退款失败：{}", e.getMessage());
            return Result.error("-1", "处理退款失败：" + e.getMessage());
        }
    }




    //依旧是抽取方法外围加上分布式锁
    public Result<?> seckill(Long userId, Long productId, Integer quantity) {
        String lockKey = "seckill:lock:" + productId + ":" + userId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 5, TimeUnit.SECONDS);
            if (!locked) {
                return Result.error("-1", "操作太频繁，请稍后再试");
            }
            //redis快速校验一人一单，库存，比数据库更快
            LocalDateTime now = LocalDateTime.now();
            String cacheKey = "seckill:product:" + productId;
            RMap<String, Object> cache = redissonClient.getMap(cacheKey);
            if (!cache.isEmpty()) {
                LocalDateTime start = LocalDateTime.parse((String) cache.get("startTime"));
                LocalDateTime end = LocalDateTime.parse((String) cache.get("endTime"));
                Integer stock = (Integer) cache.get("stock");
                // 快速校验时间、库存...
                if (now.isBefore(start) || now.isAfter(end) || stock < quantity) {
                    return Result.error("-1", "秒杀未开始/已结束/库存不足");
                }
                // 还可以用 Redis Set 检查一人一单
                String usersKey = "seckill:users:" + productId;
                if (redissonClient.getSet(usersKey).contains(userId.toString())) {
                    return Result.error("-1", "每人限购一单");
                }
            }



            //调用抽取的方法
            return this.doseckillwithlock(userId, productId, quantity);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("-1", "系统繁忙");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock(); //最终一定要释放锁
            }
        }
    }



    @Transactional
    public Result<?>  doseckillwithlock(Long id, Long productId, Integer quantity) {

        // 1. 查询秒杀商品（加行锁，防止超卖）
        SeckillProduct sp = seckillProductMapper.selectOne(
                new LambdaQueryWrapper<SeckillProduct>()
                        .eq(SeckillProduct::getProductId, productId)
                        .last("FOR UPDATE")
        );
        if (sp == null) {
            return Result.error("-1", "秒杀活动不存在");
        }
        Long  userId=id;

//        // 2. 校验时间窗口
//        LocalDateTime now = LocalDateTime.now();
//        if (now.isBefore(sp.getStartTime())) {
//            return Result.error("-1", "秒杀尚未开始");
//        }
//        if (now.isAfter(sp.getEndTime())) {
//            return Result.error("-1", "秒杀已结束");
//        }
//
//        // 3. 校验库存
//        if (sp.getStock() < quantity) {
//            return Result.error("-1", "库存不足");
//        }
//
        // 4. 一人一单校验（查询 seckill_order 表）
        Long count = seckillOrderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getUserId, userId)
                        .eq(SeckillOrder::getProductId, productId)
        );
        if (count > 0) {
            return Result.error("-1", "每人限购一单，您已秒杀过该商品");
        }

        // 5. 扣减秒杀库存
        sp.setStock(sp.getStock() - quantity);
        int update = seckillProductMapper.updateById(sp);
        if (update == 0) {
            return Result.error("-1", "库存不足，请重试");
        }









        // 6. 创建普通订单（复用 order 表）
        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setQuantity(quantity);
        order.setPrice(sp.getSeckillPrice());
        order.setTotalPrice(sp.getSeckillPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(0); // 未支付
        order.setCreatedAt(Timestamp.valueOf(LocalDateTime.now()));
        orderMapper.insert(order);

        // 7. 记录秒杀订单（唯一索引兜底防重复）
        SeckillOrder so = new SeckillOrder();
        so.setUserId(userId);
        so.setProductId(productId);
        so.setOrderId(order.getId());
        so.setSeckillPrice(sp.getSeckillPrice());
        so.setQuantity(quantity);
        so.setStatus(0);
        try {
            seckillOrderMapper.insert(so);


//
//            String orderCacheKey = "seckill:order:" + order.getId();
//            RMap<String, Object> orderCache = redissonClient.getMap(orderCacheKey);
//            orderCache.put("orderId", order.getId());
//            orderCache.put("userId", order.getUserId());
//            orderCache.put("productId", order.getProductId());
//            orderCache.put("price", order.getPrice());
//            orderCache.put("status", order.getStatus());
//            // 设置过期时间，比如 1 小时
//            orderCache.expire(1, TimeUnit.HOURS);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 事务提交后才写缓存
                    String orderCacheKey = "seckill:order:" + order.getId();
                    RMap<String, Object> orderCache = redissonClient.getMap(orderCacheKey);
                    orderCache.put("orderId", order.getId());
                    orderCache.put("userId", order.getUserId());
                    orderCache.put("productId", order.getProductId());
                    orderCache.put("price", order.getPrice());
                    orderCache.put("status", order.getStatus());
                    orderCache.expire(1, TimeUnit.HOURS);
                }
            });




        } catch (DuplicateKeyException e) {
            // 极少数并发情况下，唯一索引会捕获重复，回滚事务
            throw new RuntimeException("一人一单冲突，请勿重复提交", e);
        }

        LOGGER.info("秒杀成功，订单ID：{}，用户ID：{}，商品ID：{}", order.getId(), userId, productId);
        return Result.success(order);
    }
}