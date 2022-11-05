package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService voucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        UserDTO user = UserHolder.getUser();
        //执行lua脚本，对秒杀券下单
        stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),voucherId.toString(),user.getId().toString());
        //判断返回结果 1：库存不足 2：用户已下过单 0：可以下单

        //返回订单ID
        return Result.ok(0);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher seckillVoucher = voucherService.getById(voucherId);
        //判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动尚未开始");
        }
        //判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }
        //判断秒杀券库存
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("秒杀券已售空");
        }
        //对用户id上锁，保证每次进入createVoucherOrder方法的id都是不一样的
        Long userId = UserHolder.getUser().getId();
        //获取锁
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            //获取失败
            return Result.fail("一个用户只能购买一张");
        }
        try {
            //先获取锁在提交事务，保证线程安全
            //使用spring的AopContext获取动态代理对象来调用createVoucherOrder方法，否则该方法相当于是由this调用，·
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }*/

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();
        //统计库中对应id的用户和秒杀券，保证库中一个用户只会有一条记录
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("一个用户只能购买一张");
        }
        //秒杀完成修改库存
        boolean success = voucherService
                .update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)//使用乐观锁判断库中的值和渠道的值是否相同，避免超卖问题
                .update();
        if (!success) {
            return Result.fail("秒杀券库存不足");
        }
        //创建订单
        VoucherOrder order = new VoucherOrder();
        //订单id
        order.setId(redisIdWorker.nextId("order"));
        //用户id
        order.setUserId(userId);
        //代金券id
        order.setVoucherId(voucherId);
        //写入数据库
        save(order);
        return Result.ok(order.getId());
    }
}
