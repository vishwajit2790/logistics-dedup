package com.example.logistics.order;

import com.example.logistics.catalog.*;
import com.example.logistics.pool.CatalogCache;
import com.example.logistics.pool.DocumentPool;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {
    private final DocumentPool pool;
    private final CatalogCache cache;
    private final MongoTemplate mongo;
    private final MeterRegistry registry;

    public OrderService(DocumentPool pool, CatalogCache cache, MongoTemplate mongo, MeterRegistry registry) {
        this.pool = pool;
        this.cache = cache;
        this.mongo = mongo;
        this.registry = registry;
    }

    /** Hierarchy is created at most once (Redis-gated); an order row is ALWAYS added. */
    public Order placeOrder(PlaceOrderRequest r) {
        Timer.Sample sample = Timer.start(registry);
        try {
            pool.ensure(NetworkDoc.class, r.networkId(), Map.of("name", r.networkName()));
            pool.ensure(HubDoc.class, r.hubId(),
                    Map.of("name", r.hubName(), "networkId", r.networkId()));
            pool.ensure(FleetDoc.class, r.fleetId(),
                    Map.of("name", r.fleetName(), "mode", r.fleetMode(), "hubId", r.hubId()));
            pool.ensure(ContainerDoc.class, r.containerId(),
                    Map.of("name", r.containerName(), "fleetId", r.fleetId()));
            pool.ensure(PackageDoc.class, r.packageId(),
                    Map.of("description", r.packageDescription(),
                           "weightKg", r.weightKg(),
                           "containerId", r.containerId()));

            Order saved = mongo.insert(new Order(null, "ORD-" + UUID.randomUUID(),
                    r.customerId(), r.packageId(), r.quantity(), "PLACED", Instant.now()));
            registry.counter("orders.placed").increment();
            return saved;
        } finally {
            sample.stop(Timer.builder("orders.place").publishPercentileHistogram().register(registry));
        }
    }

    public Map<String, Object> trace(String orderNo) {
        Order o = mongo.findOne(Query.query(Criteria.where("orderNo").is(orderNo)), Order.class);
        if (o == null) return Map.of();

        PackageDoc p   = cache.get(PackageDoc.class, o.packageId());
        ContainerDoc c = cache.get(ContainerDoc.class, p.containerId());
        FleetDoc f     = cache.get(FleetDoc.class, c.fleetId());
        HubDoc h       = cache.get(HubDoc.class, f.hubId());
        NetworkDoc n   = cache.get(NetworkDoc.class, h.networkId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", o); out.put("package", p); out.put("container", c);
        out.put("fleet", f); out.put("hub", h); out.put("network", n);
        return out;
    }
}
