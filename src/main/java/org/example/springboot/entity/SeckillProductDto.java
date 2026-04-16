package org.example.springboot.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SeckillProductDto {
    private Long productId;          // 普通商品ID
    private BigDecimal seckillPrice; // 秒杀价
    private Integer stock;           // 秒杀库存
    private LocalDateTime startTime; // 开始时间
    private LocalDateTime endTime;   // 结束时间
}