package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 基于Redis Bitmap的布隆过滤器
 */
@Component
public class BloomFilter {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // bitmap大小 2^22 = 4,194,304 bits ≈ 512KB
    private static final int BITMAP_SIZE = 1 << 22;

    /**
     * 将元素加入布隆过滤器
     */
    public void add(String key, Long value) {
        int[] offsets = hashOffsets(value);
        for (int offset : offsets) {
            stringRedisTemplate.opsForValue().setBit(key, offset, true);
        }
    }

    /**
     * 判断元素是否可能存在（可能误判为存在，但不会误判为不存在）
     */
    public boolean mightContain(String key, Long value) {
        int[] offsets = hashOffsets(value);
        for (int offset : offsets) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(key, offset);
            if (bit == null || !bit) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断布隆过滤器key是否已存在
     */
    public boolean exists(String key) {
        Boolean has = stringRedisTemplate.hasKey(key);
        return has != null && has;
    }

    // 3个hash函数对应的种子值
    private int[] hashOffsets(Long value) {
        int[] offsets = new int[3];
        long v = value;
        offsets[0] = hash(v, 0x3A5C);
        offsets[1] = hash(v, 0x7B2D);
        offsets[2] = hash(v, 0x9E4F);
        return offsets;
    }

    private int hash(long value, int seed) {
        long h = seed ^ value;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return Math.abs((int) h) % BITMAP_SIZE;
    }
}
