package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 服务实现类
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
    public Result queryListByRedis() {
        //缓存中查询list
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_LIST);
        //如果不为空证明缓存中有，返回
        if (StrUtil.isNotBlank(shopJson)) {
            List<ShopType> shopList = JSONUtil.toList(shopJson, ShopType.class);
            return Result.ok(shopList);
        }
        //缓存中没有就到数据中查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //判断数据是否存在
        if (shopTypeList.isEmpty() || shopTypeList == null) {
            return Result.fail("该店铺类型不存在");
        }
        String json = JSONUtil.toJsonStr(shopTypeList);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_LIST, json);
        return Result.ok(shopTypeList);
    }
}
