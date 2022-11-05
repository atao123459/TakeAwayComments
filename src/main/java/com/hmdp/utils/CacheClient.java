package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * redis工具类
 * 封装了redis的保存方法
 *
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    //重建缓存线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 保存数据到redis
     * @param key redis的key
     * @param value redis的value
     * @param time 过期时间
     * @param unit 时间单位（时，分，秒）
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 使用逻辑过期策略保存数据到redis
     * 使用RedisData设置过期时间
     * @param key redis的key
     * @param value redis的value
     * @param time 过期时间
     * @param unit 时间单位（时，分，秒）
     */

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 通过redis查询店铺
     * 用户进行查询操作时，先查询缓存，缓存存在直接返回，不存在则查数据库
     * 添加了若查出数据为空时，插入一条空的数据到redis中，避免出现缓存穿透问题
     * 添加锁之后，如果查询的数据不再缓存中，就获取锁然后查询数据库
     * @param keyPreFix key前缀
     * @param id 传入的id
     * @param type 数据类型
     * @param dbFeedBack 调用数据库查询的lambda
     * @param <R> 返回值泛型
     * @param <ID> id泛型
     * @return redis查询结果
     */
    public <R,ID> R queryShopPassThrough(String keyPreFix, ID id, Class<R> type, Long time, TimeUnit unit, Function<ID,R> dbFeedBack){
        String key = keyPreFix + id;
        //查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        //判断命中是否为空值
        if (json != null) {
            //为空值返回错误信息
            return null;
        }
        //缓存中没有就去数据库查
        //由于工具类无法查询数据库，这里是使用lambda
        R r = dbFeedBack.apply(id);
        //数据库中没有该店铺信息就返回错误提示
        if(r == null){
            //若店铺为空，将空值存入redis中，有效期两分钟
            this.set(key, null,time,unit);
            return null;
        }
        //数据库中存在就把该店铺信息写入缓存中
        this.set(key,r,time,unit);
        //返回查讯到的数据
        return r;
    }


    /**
     * 使用逻辑过期方法保存店铺信息到redis中
     * 逻辑过期避免缓存击穿
     * 1、查询缓存判断是否命中
     * 2、命中后判断缓存是否过期
     * 3、缓存为过期返回店铺信息，过期则尝试获取锁
     * 4、获取到锁后开启新线程查询数据库，然后更新缓存和过期时间；获取不到就返回过期的信息
     * @param keyPreFix key前缀
     * @param id 传入的id
     * @param type 数据类型
     * @param dbFeedBack 调用数据库查询的lambda
     * @param <R> 返回值泛型
     * @param <ID> id泛型
     * @return redis查询结果
     */
    public <R,ID> R queryWithLogicalExpire(String keyPreFix, ID id, Class<R> type, Long time, TimeUnit unit, Function<ID,R> dbFeedBack){
        String key = keyPreFix + id;
        //查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isBlank(json)){
            return null;
        }
        //查询缓存命中,json转为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //没有过期，直接返回数据
            return r;
        }
        //过期需要重建缓存
        //先获取互斥锁
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        if(isLock){
            //获取到锁,开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R res = dbFeedBack.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,res,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        //返回查讯到的数据
        return r;
    }

    /**
     * redis互斥锁
     * 获取锁
     * 原理为使用redis的setnx命令，当key存在时不允许修改
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //使用工具类对flag进行拆箱，如果直接返回会有空值问题
        return BooleanUtil.isTrue(flag);
    }

    /**
     * redis互斥锁
     * 释放锁
     * 在完成操作之后将原先使用setnx命令写入的key删掉，保证后序的操作可以正常获取到锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
