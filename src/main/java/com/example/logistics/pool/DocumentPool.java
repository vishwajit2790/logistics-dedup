package com.example.logistics.pool;

import com.example.logistics.config.CacheProperties;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Object pool / flyweight factory. Redis gates repeat lookups; MongoDB's atomic upsert
 * guarantees no duplicates even when Redis is cold or down.
 *
 * Metrics: pool.ensure{type,result=redis_hit|created|existing}, redis.fallback{op}
 */
@Component
public class DocumentPool {

    private static final Logger log = LoggerFactory.getLogger(DocumentPool.class);

    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final Duration ttl;

    public DocumentPool(MongoTemplate mongo, StringRedisTemplate redis, MeterRegistry registry,
                        CacheProperties props) {
        this.mongo = mongo;
        this.redis = redis;
        this.registry = registry;
        this.ttl = props.ttl();
    }

    public <T> void ensure(Class<T> type, String id, Map<String, Object> fieldsOnInsert) {
        String typeName = type.getSimpleName();
        String key = "pool:exists:" + typeName + ":" + id;

        if (existsInRedis(key)) {
            count(typeName, "redis_hit");
            return;
        }

        Update update = new Update();
        fieldsOnInsert.forEach(update::setOnInsert);
        String result;
        try {
            UpdateResult r = mongo.upsert(Query.query(Criteria.where("_id").is(id)), update, type);
            result = r.getUpsertedId() != null ? "created" : "existing";
        } catch (DuplicateKeyException e) {
            result = "existing";               // concurrent creator won the race
        }
        count(typeName, result);
        markInRedis(key);
    }

    private void count(String type, String result) {
        registry.counter("pool.ensure", "type", type, "result", result).increment();
    }

    private boolean existsInRedis(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            registry.counter("redis.fallback", "op", "exists").increment();
            log.warn("Redis unavailable, falling back to MongoDB: {}", e.getMessage());
            return false;
        }
    }

    private void markInRedis(String key) {
        try {
            long jitter = ThreadLocalRandom.current().nextLong(ttl.toSeconds() / 10 + 1);
            redis.opsForValue().set(key, "1", ttl.plusSeconds(jitter));
        } catch (Exception e) {
            registry.counter("redis.fallback", "op", "mark").increment();
            log.warn("Redis write skipped: {}", e.getMessage());
        }
    }
}
