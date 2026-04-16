package org.example.springboot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;

@Data
public class Category implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "分类名称不能为空")
    private String name;
    private String icon;
    private String description;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    @TableField(exist = false)
    private Integer productCount;
} 