package com.example.demo_01.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(SqlSessionFactory.class)
@MapperScan("com.example.demo_01.user.mapper")
public class MyBatisFlexMapperConfig {
}
