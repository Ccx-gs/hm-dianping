package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 定义存入 Redis 的 Key
        String key = "cache:shop-type";
        // 1. 从 Redis 中查询商铺缓存 (因为是一组数据，我们通常把它转成 JSON 字符串存进去)
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断缓存是否存在
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 3. 存在，直接将 JSON 字符串反序列化为 List 集合，并返回
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }

        // 4. 不存在，则去查询数据库 (根据 sort 字段排序)
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 5. 判断数据库中是否有数据
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("分类不存在！");
        }

        // 6. 数据库存在数据，将其转换为 JSON 字符串存入 Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));

         stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        // 7. 返回数据库查到的数据
        return Result.ok(typeList);
    }
}
