package com.example.logistics.order;

import com.example.logistics.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderDeduplicationIT extends AbstractIntegrationTest {

    @Autowired OrderService service;
    @Autowired MongoTemplate mongo;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry registry;

    @BeforeEach
    void clean() {
        mongo.getCollectionNames().forEach(c -> mongo.remove(new Query(), c));
        flushRedis();
        registry.clear();
    }

    private void flushRedis() {
        redis.execute((RedisCallback<Object>) c -> { c.serverCommands().flushAll(); return null; });
    }

    private PlaceOrderRequest req() {
        return new PlaceOrderRequest("CUST-1", 1,
                "NET-FEDEX", "FedEx Global",
                "HUB-APAC", "Asia-Pacific Hub",
                "FLT-APAC-CARGO", "Custom Cargo Fleet", "Sea/Road",
                "CON-77", "Freight Box #CON-77",
                "PKG-1001", "Box with tracking ID #PKG-1001", 12.5);
    }

    private long count(String collection) {
        return mongo.count(new Query(), collection);
    }

    private void assertHierarchyCreatedOnce(long expectedOrders) {
        assertEquals(1, count("logistics_networks"));
        assertEquals(1, count("regional_hubs"));
        assertEquals(1, count("transport_fleets"));
        assertEquals(1, count("shipping_containers"));
        assertEquals(1, count("packages"));
        assertEquals(expectedOrders, count("orders"));
    }

    @Test
    void sameOrderTwice_createsHierarchyOnce_butTwoOrders() {
        service.placeOrder(req());
        service.placeOrder(req());

        assertHierarchyCreatedOnce(2);
    }

    @Test
    void coldRedisBetweenOrders_stillNoDuplicates() {
        service.placeOrder(req());
        flushRedis();                       // simulate restart / eviction
        service.placeOrder(req());

        assertHierarchyCreatedOnce(2);
    }

    @Test
    void twentyConcurrentOrders_noDuplicateHierarchy() throws Exception {
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Order>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> { start.await(); return service.placeOrder(req()); }));
        }
        start.countDown();
        for (Future<Order> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertHierarchyCreatedOnce(n);
    }

    @Test
    void metrics_showOneCreate_andOneRedisHit_forRepeatOrder() {
        service.placeOrder(req());
        service.placeOrder(req());

        assertEquals(1.0, registry.counter("pool.ensure", "type", "PackageDoc", "result", "created").count());
        assertEquals(1.0, registry.counter("pool.ensure", "type", "PackageDoc", "result", "redis_hit").count());
        assertEquals(2.0, registry.counter("orders.placed").count());
    }

    @Test
    void trace_walksFullHierarchy_andSecondTraceHitsRedis() {
        Order order = service.placeOrder(req());

        Map<String, Object> first = service.trace(order.orderNo());
        Map<String, Object> second = service.trace(order.orderNo());

        assertEquals(6, first.size());     // order, package, container, fleet, hub, network
        assertEquals(first, second);
        assertEquals(1.0, registry.counter("catalog.cache", "type", "PackageDoc", "result", "miss").count());
        assertEquals(1.0, registry.counter("catalog.cache", "type", "PackageDoc", "result", "hit").count());
        assertTrue(registry.counter("catalog.cache", "type", "NetworkDoc", "result", "hit").count() >= 1);
    }
}
