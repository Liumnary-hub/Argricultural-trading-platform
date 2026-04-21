-- KEYS[1]: 库存key  seckill:stock:productId
-- KEYS[2]: 用户集合key seckill:users:productId
-- ARGV[1]: productId
-- ARGV[2]: userId
-- ARGV[3]: quantity
-- ARGV[4]: orderId

local stockKey = KEYS[1]
local usersKey = KEYS[2]
local productId = ARGV[1]
local userId = ARGV[2]
local quantity = tonumber(ARGV[3])
local orderId = ARGV[4]

-- 1. 检查库存
local stock = redis.call('get', stockKey)
if not stock or tonumber(stock) < quantity then
    return {"err", "库存不足"}
end

-- 2. 一人一单检查
local isMember = redis.call('sismember', usersKey, userId)
if tonumber(isMember) == 1 then
    return {"err", "重复下单"}
end

-- 3. 扣减库存
redis.call('decrby', stockKey, quantity)
-- 4. 记录用户
redis.call('sadd', usersKey, userId)

-- 5. 发送消息到 Stream
redis.call('xadd', 'stream:seckill_order', '*',
    'productId', productId,
    'userId', userId,
    'orderId', orderId,
    'quantity', quantity)

return {"ok", orderId}