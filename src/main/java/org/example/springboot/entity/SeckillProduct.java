package org.example.springboot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_product")
public class SeckillProduct {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private BigDecimal seckillPrice;
    private Integer stock;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer version; // 可选乐观锁
}