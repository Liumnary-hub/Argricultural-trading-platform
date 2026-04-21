package org.example.springboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.springboot.entity.Order;
import org.example.springboot.entity.SeckillOrder;
import org.example.springboot.entity.SeckillProduct;
import org.example.springboot.mapper.OrderMapper;
import org.example.springboot.mapper.SeckillOrderMapper;
import org.example.springboot.mapper.SeckillProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@Service
@Slf4j
@Transactional
public class SeckillOrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillOrderMapper seckillOrderMapper;
    @Autowired
    private SeckillProductMapper seckillProductMapper;

    public void createOrderAsync(Long orderId, Long userId, Long productId, Integer quantity) {
        // 幂等检查：订单是否已存在
        if (orderMapper.selectById(orderId) != null) {
            return;
        }

        // 查询秒杀商品信息（仅获取价格等，不再扣减库存）
        SeckillProduct sp = seckillProductMapper.selectOne(
                new LambdaQueryWrapper<SeckillProduct>()
                        .eq(SeckillProduct::getProductId, productId)
        );
        if (sp == null) {
            throw new RuntimeException("秒杀商品不存在");
        }

        // 创建普通订单
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setProductId(productId);
        order.setQuantity(quantity);
        order.setPrice(sp.getSeckillPrice());
        order.setTotalPrice(sp.getSeckillPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(0); // 未支付
        order.setCreatedAt(Timestamp.valueOf(LocalDateTime.now()));
        orderMapper.insert(order);

        // 创建秒杀订单记录
        SeckillOrder so = new SeckillOrder();
        so.setUserId(userId);
        so.setProductId(productId);
        so.setOrderId(orderId);
        so.setSeckillPrice(sp.getSeckillPrice());
        so.setQuantity(quantity);
        so.setStatus(0);
        seckillOrderMapper.insert(so);
    }
}