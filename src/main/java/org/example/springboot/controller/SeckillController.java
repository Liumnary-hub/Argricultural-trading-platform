package org.example.springboot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.springboot.common.Result;
import org.example.springboot.entity.Product;
import org.example.springboot.entity.SeckillProduct;
import org.example.springboot.entity.SeckillProductDto;
import org.example.springboot.entity.User;
import org.example.springboot.mapper.ProductMapper;
import org.example.springboot.mapper.SeckillProductMapper;
import org.example.springboot.service.OrderService;
import org.example.springboot.service.SeckillOrderService;
import org.example.springboot.service.SeckillService;
import org.example.springboot.util.JwtTokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/seckill")
public class SeckillController {
    @Autowired
    private OrderService orderService;
    private static final Logger LOGGER = LoggerFactory.getLogger(SeckillController.class);
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private SeckillProductMapper seckillProductMapper;

    @Autowired
    private SeckillService seckillService;


    @PostMapping("/{productId}")
    public Result<?> seckill(@PathVariable Long productId,
                             @RequestParam(defaultValue = "1") Integer quantity)
    {
        System.out.println("====== SeckillController.seckill 被调用，商品ID：" + productId + "，数量：" + quantity);


        User currentUser = JwtTokenUtils.getCurrentUser();//从token中拿到相关用户
        if(currentUser==null){
            return Result.error("500","未登录，请先登录");
        }

        return seckillService.seckill(currentUser.getId(),productId,quantity);
    }




    @PostMapping("/product")
    public Result<?> createSeckillProduct(@RequestBody SeckillProductDto dto) {
        // 1. 获取当前登录用户（商家）
        User currentUser = JwtTokenUtils.getCurrentUser();
        if (currentUser == null) {
            return Result.error("-1", "未登录");
        }
        // 假设商家角色的标识为 "merchant"（根据你的 User.role 字段）
        if (!"merchant".equals(currentUser.getRole())) {
            return Result.error("-1", "无权限，仅商家可操作");
        }

        // 2. 参数校验
        if (dto.getProductId() == null || dto.getProductId() <= 0) {
            return Result.error("-1", "商品ID不能为空");
        }
        if (dto.getSeckillPrice() == null || dto.getSeckillPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("-1", "秒杀价格必须大于0");
        }
        if (dto.getStock() == null || dto.getStock() <= 0) {
            return Result.error("-1", "秒杀库存必须大于0");
        }
        if (dto.getStartTime() == null || dto.getEndTime() == null) {
            return Result.error("-1", "秒杀开始和结束时间不能为空");
        }
        if (dto.getStartTime().isAfter(dto.getEndTime())) {
            return Result.error("-1", "开始时间不能晚于结束时间");
        }

        // 3. 校验商品是否存在，并且属于当前商家
        Product product = productMapper.selectById(dto.getProductId());
        if (product == null) {
            return Result.error("-1", "商品不存在");
        }
        if (!product.getMerchantId().equals(currentUser.getId())) {
            return Result.error("-1", "只能为自己店铺的商品创建秒杀活动");
        }

        // 4. 检查该商品是否已经配置了秒杀活动（防止重复）
        SeckillProduct existing = seckillProductMapper.selectOne(
                new LambdaQueryWrapper<SeckillProduct>()
                        .eq(SeckillProduct::getProductId, dto.getProductId())
        );
        if (existing != null) {
            return Result.error("-1", "该商品已存在秒杀活动，请勿重复创建");
        }

        // 5. 构建秒杀商品对象并保存
        SeckillProduct sp = new SeckillProduct();
        sp.setProductId(dto.getProductId());
        sp.setSeckillPrice(dto.getSeckillPrice());
        sp.setStock(dto.getStock());
        sp.setStartTime(dto.getStartTime());
        sp.setEndTime(dto.getEndTime());
        sp.setVersion(0); // 乐观锁初始版本
        int rows = seckillProductMapper.insert(sp);
        if (rows > 0) {
            LOGGER.info("商家 {} 为商品 {} 创建秒杀活动成功", currentUser.getId(), dto.getProductId());
            return Result.success(sp);
        } else {
            return Result.error("-1", "创建秒杀活动失败");
        }
    }





}
