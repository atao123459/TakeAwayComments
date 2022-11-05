package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 使用逻辑过期方法来避免缓存击穿
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
