package org.example.springboot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.springboot.entity.SeckillProduct;

@Mapper
public interface SeckillProductMapper extends BaseMapper<SeckillProduct> {


//    int updateStockToZero(Long productId);
}
