package com.example.logistics.pool;

import com.example.logistics.config.CacheProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Read-through cache for pooled (immutable) documents.
 * Metrics: catalog.cache{type,result=hit|miss}, redis.fallback{op}
 */
@Component
public class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private final MongoTemplate mongo;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry registry;
    private final Duration ttl;

    public CatalogCache(MongoTemplate mongo, StringRedisTemplate redis, ObjectMapper json,
                        MeterRegistry registry, CacheProperties props) {
        this.mongo = mongo;
        this.redis = redis;
        this.json = json;
        this.registry = registry;
        this.ttl = props.ttl();
    }

    public <T> T get(Class<T> type, String id) {
        String typeName = type.getSimpleName();
        String key = "pool:doc:" + typeName + ":" + id;

        try {
            String hit = redis.opsForValue().get(key);
            if (hit != null) {
                registry.counter("catalog.cache", "type", typeName, "result", "hit").increment();
                return json.readValue(hit, type);
            }
        } catch (Exception e) {
            registry.counter("redis.fallback", "op", "read").increment();
            log.warn("Redis read failed, using MongoDB: {}", e.getMessage());
        }

        registry.counter("catalog.cache", "type", typeName, "result", "miss").increment();
        T doc = mongo.findById(id, type);
        if (doc != null) {
            try {
                long jitter = ThreadLocalRandom.current().nextLong(ttl.toSeconds() / 10 + 1);
                redis.opsForValue().set(key, json.writeValueAsString(doc), ttl.plusSeconds(jitter));
            } catch (Exception e) {
                registry.counter("redis.fallback", "op", "write").increment();
                log.warn("Redis write skipped: {}", e.getMessage());
            }
        }
        return doc;
    }
}
