package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 通用缓存工具类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ==================== 写入 ====================

    // 写入缓存
    public void set(String key, Object value, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), ttl, unit);
    }

    // 缓存空值，防止穿透
    public void setNull(String key, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, "", ttl, unit);
    }

    // ==================== 查询：缓存穿透方案（缓存空值） ====================

    public <T> T queryWithPassThrough(
            String key, Class<T> type, Supplier<T> dbFallback,
            Long ttl, TimeUnit unit, Long nullTtl) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 命中空值，避免穿透
        if (json != null) {
            return null;
        }
        T result = dbFallback.get();
        if (result == null) {
            setNull(key, nullTtl, TimeUnit.MINUTES);
            return null;
        }
        set(key, result, ttl, unit);
        return result;
    }

    // ==================== 查询：缓存击穿方案（互斥锁） ====================

    public <T> T queryWithMutex(
            String key, String lockKey, Class<T> type, Supplier<T> dbFallback,
            Long ttl, TimeUnit unit, Long nullTtl) {
        // 1. 查Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        // 2. 获取互斥锁
        if (!tryLock(lockKey)) {
            // 3. 失败，休眠后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return queryWithMutex(key, lockKey, type, dbFallback, ttl, unit, nullTtl);
        }
        try {
            // 4. 双重检查
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }
            // 5. 查数据库
            T result = dbFallback.get();
            // 6. 不存在，缓存空值
            if (result == null) {
                setNull(key, nullTtl, TimeUnit.MINUTES);
                return null;
            }
            // 7. 存在，写入Redis
            set(key, result, ttl, unit);
            return result;
        } finally {
            // 8. 释放锁
            unlock(lockKey);
        }
    }

    // ==================== 互斥锁 ====================

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
