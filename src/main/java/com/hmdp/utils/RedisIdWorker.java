package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 全局唯一ID生成器
 * ID结构: 1位符号位 + 31位时间戳 + 32位序列号
 * 序列号由Redis自增生成，key按天划分，每天从1开始
 */
@Component
public class RedisIdWorker {

    // 起始时间戳 (2022-01-01 00:00:00 的秒数)
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // Redis key前缀
    private static final String KEY_PREFIX = "icr:";

    //序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId() {
        // 1. 当天日期key（如 icr:20250603），每天自动换新key，序列号重新计数
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key = KEY_PREFIX + today;

        // 2. Redis自增获取序列号（原子操作，天然线程安全）
        Long sequence = stringRedisTemplate.opsForValue().increment(key);
        // 首次创建key时设置过期时间(1天)，避免key堆积
        if (sequence != null && sequence == 1L) {
            stringRedisTemplate.expire(key, 1, TimeUnit.DAYS);
        }

        // 3. 31位时间戳
        long now = System.currentTimeMillis() / 1000;
        long timestampDelta = now - BEGIN_TIMESTAMP;

        // 4. 位运算拼接: 高31位时间戳 | 低32位序列号
        return (timestampDelta << COUNT_BITS) | sequence;
    }
}
