package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
        // 布隆过滤器预检，拦截不存在的id，防止缓存穿透
        if (!bloomFilter.mightContain(BLOOM_FILTER_SHOP_KEY, id)) {
            return Result.fail("商铺不存在!");
        }
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺信息
        String shopJson =  stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.数据库不存在，返回错误
        if (shop == null) {
            return Result.fail("商铺不存在!");
        }
        //6.数据库存在，写入redis，返回商铺信息
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空!");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
