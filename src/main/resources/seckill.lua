--参数列表
--优惠券ID
local voucherId = ARGV[1]
--用户列表
local userId = ARGV[2]
--库存key
local stockKey = 'seckill:stock' .. voucherId
--订单key
local orderKey = 'seckill:stock' .. voucherId

--业务
--判断库存是否小于0
if(tonumber(redis.call('get',stockKey)) <= 0) then
    --库存不足返回1
    return 1
end

--判断用户是否下过单
if(redis.call('sismember',orderKey,userId) == 1) then
    --用户下过单，返回2
    return 2
end

--扣除库存
redis.call('incrby',stockKey,-1)
--保存用户
redis.call('sadd',orderKey,userId)