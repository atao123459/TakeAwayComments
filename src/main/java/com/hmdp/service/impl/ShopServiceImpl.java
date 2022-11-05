package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryByRedis(Long id) {
        //解决缓存穿透问题
        Shop shop = cacheClient.queryShopPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES,(o) -> getById(o));
        //解决缓存击穿问题
//        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES, this::getById);
        if(shop == null){
            return Result.fail("店铺信息不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 通过redis查询店铺
     * 用户进行查询操作时，先查询缓存，缓存存在直接返回，不存在则查数据库
     * 添加了互斥锁，避免出现缓存击穿问题
     * 添加锁之后，如果查询的数据不再缓存中，就获取锁然后查询数据库
     * @param id
     * @return
     */
//    public Shop queryShopMutex(Long id){
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        //查询缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            Shop shop = BeanUtil.toBean(shopJson,Shop.class);
//            return shop;
//        }
//        //判断命中是否为空值
//        if (shopJson != null) {
//            //为空值返回错误信息
//            return null;
//        }
//        //缓存重建，获取锁
//        Shop shop = null;
//        try {
//        boolean b = tryLock(RedisConstants.LOCK_SHOP_KEY);
//            if(!b){
//                //获取失败，休眠一段时间再尝试获取
//                Thread.sleep(50);
//                queryShopMutex(id);
//            }
//            //获取锁成功就去数据库查
//            shop = getById(id);
//            //数据库中没有该店铺信息就返回错误提示
//            if(shop == null){
//                //若店铺为空，将空值存入redis中，有效期两分钟
//                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
//                return null;
//            }
//            //数据库中存在就把该店铺信息写入缓存中
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //释放锁
//            unLock(RedisConstants.LOCK_SHOP_KEY);
//        }
//        //返回查讯到的数据
//        return shop;
//    }


    /**
     * 使用redis对店铺信息进行缓存
     * 修改店铺时先写数据库再删除缓存
     * @param shop
     * @return
     */
    @Override
    //事务注解
    @Transactional
    public Result updateByCache(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("该店铺不存在");
        }
        //先写数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
//        //删除之后再写入缓存
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop));
        return Result.ok();
    }

//    public void saveShopToRedis(Long id,Long expireSeconds){
//        //查询数据库的店铺信息
//        Shop shop = getById(id);
//        //封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        //设置过期时间
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        //写入redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY,JSONUtil.toJsonStr(redisData));
//    }
}
