-- KEYS[1] = "ratelimit:sliding:<clientId>"
-- ARGV[1] = limit (e.g., 5)
-- ARGV[2] = window size in milliseconds (e.g., 60000)
-- ARGV[3] = current timestamp in milliseconds (now)
-- ARGV[4] = unique request ID (so two requests in the exact same millisecond don't overwrite each other)

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local member = ARGV[4]

-- 1. Cross out all timestamps older than (now - window_ms)
redis.call('ZREMRANGEBYSCORE', key, 0, now - window_ms)

-- 2. Count how many timestamps are left in the notebook
local current_count = redis.call('ZCARD', key)

-- 3. Decide: Allow or Block?
if current_count < limit then
    redis.call('ZADD', key, now, member)
    redis.call('PEXPIRE', key, window_ms)
    return {1, limit - current_count - 1}
else
    return {0, 0}
end