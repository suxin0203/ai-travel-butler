package com.suxin.travel.traveljava.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局 Jackson 配置（唯一的 ObjectMapper Bean，统一注入使用）。
 * 两个关键点：
 * 1. 时间格式统一 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd，与 API 契约 §1.5 一致；
 * 2. FAIL_ON_UNKNOWN_PROPERTIES 关闭——模型输出的 JSON 结构有波动，多一个字段不能让整个解析失败。
 * 注意：本项目实体全部是 AUTO 自增主键，Long 不会超出 JS 安全范围，
 * 因此不做 Long→String 全局转换，保持契约中数值字段的类型约定。
 */
@Configuration
public class JacksonConfig {

    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        JavaTimeModule javaTime = new JavaTimeModule();
        javaTime.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATETIME_FMT));
        javaTime.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATETIME_FMT));
        javaTime.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FMT));
        javaTime.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FMT));
        mapper.registerModule(javaTime);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 模型输出/前端入参里多出的未知字段一律忽略
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
