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
import com.hmdp.utils.CacheClient;
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

    @Resource
    private CacheClient cacheClient;

    // 启动时初始化布隆过滤器
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

    // 新增商铺时同步布隆过滤器
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
        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY + id,                // 缓存key
                LOCK_SHOP_KEY + id,                 // 锁key
                Shop.class,                         // 返回类型
                () -> getById(id),                  // DB查询回调
                CACHE_SHOP_TTL, TimeUnit.MINUTES,   // 缓存有效期
                CACHE_NULL_TTL                      // 空值有效期
        );
        if (shop == null) {
            return Result.fail("商铺不存在!");
        }
        return Result.ok(shop);
    }

    // ==================== 以下为两种缓存方案的调用示例（供学习参考） ====================
    //
    // // 方案一：缓存穿透（仅缓存空值，无互斥锁）
    // private Shop queryWithPassThrough(Long id) {
    //     return cacheClient.queryWithPassThrough(
    //             CACHE_SHOP_KEY + id, Shop.class, () -> getById(id),
    //             CACHE_SHOP_TTL, TimeUnit.MINUTES, CACHE_NULL_TTL);
    // }
    //
    // // 方案二：缓存击穿（互斥锁 + 缓存空值）
    // private Shop queryWithMutex(Long id) {
    //     return cacheClient.queryWithMutex(
    //             CACHE_SHOP_KEY + id, LOCK_SHOP_KEY + id, Shop.class, () -> getById(id),
    //             CACHE_SHOP_TTL, TimeUnit.MINUTES, CACHE_NULL_TTL);
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
