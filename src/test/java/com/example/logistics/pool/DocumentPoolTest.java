package com.example.logistics.pool;

import com.example.logistics.catalog.PackageDoc;
import com.example.logistics.config.CacheProperties;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.BsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentPoolTest {

    MongoTemplate mongo;
    StringRedisTemplate redis;
    SimpleMeterRegistry registry;
    DocumentPool pool;

    final Map<String, Object> fields = Map.of("description", "Box", "weightKg", 1.0, "containerId", "CON-77");

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        registry = new SimpleMeterRegistry();
        pool = new DocumentPool(mongo, redis, registry, new CacheProperties(Duration.ofHours(1)));
    }

    private double count(String result) {
        return registry.counter("pool.ensure", "type", "PackageDoc", "result", result).count();
    }

    @Test
    void redisHit_neverTouchesMongo() {
        when(redis.hasKey(anyString())).thenReturn(true);

        pool.ensure(PackageDoc.class, "PKG-1001", fields);

        verifyNoInteractions(mongo);
        assertEquals(1.0, count("redis_hit"));
    }

    @Test
    void redisMiss_upsertsOnce_andMarksRedis() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(mongo.upsert(any(Query.class), any(UpdateDefinition.class), eq(PackageDoc.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, new BsonString("PKG-1001")));

        pool.ensure(PackageDoc.class, "PKG-1001", fields);

        verify(mongo, times(1)).upsert(any(Query.class), any(UpdateDefinition.class), eq(PackageDoc.class));
        verify(redis.opsForValue()).set(eq("pool:exists:PackageDoc:PKG-1001"), eq("1"), any(Duration.class));
        assertEquals(1.0, count("created"));
    }

    @Test
    void redisDown_failsOpen_andStillUsesMongo() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(mongo.upsert(any(Query.class), any(UpdateDefinition.class), eq(PackageDoc.class)))
                .thenReturn(UpdateResult.acknowledged(1, 0L, null));

        assertDoesNotThrow(() -> pool.ensure(PackageDoc.class, "PKG-1001", fields));

        verify(mongo).upsert(any(Query.class), any(UpdateDefinition.class), eq(PackageDoc.class));
        assertEquals(1.0, registry.counter("redis.fallback", "op", "exists").count());
        assertEquals(1.0, count("existing"));
    }

    @Test
    void concurrentCreatorWinsRace_duplicateKeyIsSwallowed() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(mongo.upsert(any(Query.class), any(UpdateDefinition.class), eq(PackageDoc.class)))
                .thenThrow(new DuplicateKeyException("E11000"));

        assertDoesNotThrow(() -> pool.ensure(PackageDoc.class, "PKG-1001", fields));
        assertEquals(1.0, count("existing"));
    }
}
