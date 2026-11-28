package com.suxin.travel.traveljava.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：扫描 mapper 包，实体用注解标记表名即可，
 * 简单 CRUD 全部走 BaseMapper，不写 XML。
 */
@Configuration
@MapperScan("com.suxin.travel.traveljava.mapper")
public class MybatisPlusConfig {
}
