package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.BloomFilter;
import com.hmdp.utils.LoginInterceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private BloomFilter bloomFilter;

    // 启动时初始化布隆过滤器，加载已有商铺id
    @PostConstruct
    public void initBloomFilter() {
        if (bloomFilter.exists(BLOOM_FILTER_SHOP_KEY)) {
            return;
        }
        List<Shop> shops = list();
        for (Shop shop : shops) {
            bloomFilter.add(BLOOM_FILTER_SHOP_KEY, shop.getId());
        }
    }

    // 新增商铺时同步添加至布隆过滤器
    public void addShopToBloomFilter(Long id) {
        bloomFilter.add(BLOOM_FILTER_SHOP_KEY, id);
    }

    @Override
    public Result queryById(Long id) {
        // 缓存穿透：布隆过滤器预检
        if (!bloomFilter.mightContain(BLOOM_FILTER_SHOP_KEY, id)) {
            log.debug("布隆过滤器拦截无效id: {}", id);
            return Result.fail("商铺不存在!");
        }
        // 缓存击穿+穿透：互斥锁重建缓存
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在!");
        }
        return Result.ok(shop);
    }

    // ==================== 互斥锁 ====================

    // 获取互斥锁，有效期10秒
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    // 释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // ==================== 缓存击穿+穿透综合方案 ====================

    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 查Redis缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 命中空值，防止穿透
        if (shopJson != null) {
            return null;
        }
        // 2. 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (!tryLock(lockKey)) {
            // 3. 获取失败，休眠后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return queryWithMutex(id);
        }
        try {
            // 4. 双重检查：其他线程可能已重建缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                return null;
            }
            // 5. 查数据库
            Shop shop = getById(id);
            // 6. 数据库不存在，缓存空值防穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 7. 数据库存在，写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        } finally {
            // 8. 释放锁
            unlock(lockKey);
        }
    }

    // ==================== 原始缓存穿透方案（供学习参考） ====================
    //
    // private Shop queryWithPassThrough(Long id) {
    //     String key = CACHE_SHOP_KEY + id;
    //     String shopJson = stringRedisTemplate.opsForValue().get(key);
    //     if (StrUtil.isNotBlank(shopJson)) {
    //         return JSONUtil.toBean(shopJson, Shop.class);
    //     }
    //     Shop shop = getById(id);
    //     if (shop == null) {
    //         return null;
    //     }
    //     stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //     return shop;
    // }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空!");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
