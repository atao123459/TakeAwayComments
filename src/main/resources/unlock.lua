--获取线程标识
local id = redis.call('get',KEYS[1])
--判断是否为该线程的锁
if(id == ARGV[1]) then
    --释放锁
    return redis.call('del',KEYS[1])
end
return 0