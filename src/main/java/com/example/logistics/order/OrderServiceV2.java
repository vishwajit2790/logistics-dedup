package com.example.logistics.order;

import com.example.logistics.catalog.*;
import com.example.logistics.pool.CatalogCache;
import com.example.logistics.pool.DocumentPool;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.ReplaceOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class OrderServiceV2 {

    private final DocumentPool pool;
    private final CatalogCache cache;
    private final MongoTemplate mongo;
    private final MeterRegistry registry;

    public OrderServiceV2(DocumentPool pool, CatalogCache cache, MongoTemplate mongo, MeterRegistry registry) {
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
        //List<Order> o = mongo.find(Query.query(Criteria.where("orderNo").is(orderNo)), Order.class);
        Order o = fetchOrder(orderNo);
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


    private Order fetchOrder(String orderNo){
        Long count = mongo.exactCount(Query.query(Criteria.where("orderNo").exists(true)), Order.class);
        System.out.println(count);
        List<Order> list = mongo.findAll(Order.class);
        for (Order value : list) {
            if (value.orderNo().equals(orderNo)) return value;
        }
        return null;
    }

    public void updateOrdersByBatch(){
        long count = mongo.exactCount(Query.query(Criteria.where("orderNo").exists(true)), Order.class);
        System.out.println(count);
        List<Order> list = mongo.findAll(Order.class);
        executeBatches(list, count);
    }

    private static Order getOrder(String orderNo, List<Order> list) {
        for (Order value : list) {
            if (value.orderNo().equals(orderNo)) return value;
        }
        return null;
    }

    private List<List<Order>> specifyBatchSize(int batchSize, List<Order> list, long count){
        int totalSize = (int) count;
        int batches = (totalSize+batchSize-1)/batchSize;
        List<List<Order>> batchesList = new ArrayList<>();
        for (int i = 0; i < batches; i++) {
            int start = i*batchSize;
            int end = Math.min(totalSize, (i+1)*batchSize);
            batchesList.add(list.subList(start, end));
        }
        return batchesList;
    }

    private void executeBatches(List<Order> list, long count){
        List<List<Order>> batches = specifyBatchSize(50, list, count);

        List<CompletableFuture<Void>> futures = batches
            .stream()
            .map(
                batch -> CompletableFuture.runAsync(() -> processBatch(batch))
            )
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    public void updateQtys() {
        List<Order> list = mongo.findAll(Order.class);
        processBatch(list);
    }

    private void processBatch(List<Order> list) {
        for (Order o : list){
            System.out.println("order_id: "+o.id());
            PackageDoc p = mongo.findOne(Query.query(Criteria.where("id").is(o.packageId())), PackageDoc.class);

            ContainerDoc c = mongo.findOne(Query.query(Criteria.where("id").is(p.containerId())), ContainerDoc.class);

            FleetDoc f = mongo.findOne(Query.query(Criteria.where("id").is(c.fleetId())), FleetDoc.class);

            HubDoc h = mongo.findOne(Query.query(Criteria.where("id").is(f.hubId())), HubDoc.class);

            NetworkDoc n = mongo.findOne(Query.query(Criteria.where("id").is(h.networkId())), NetworkDoc.class);

            PlaceOrderRequest r = new PlaceOrderRequest(o.customerId(), o.quantity(),
                n.id(), n.name(),
                h.id(), h.name(),
                f.id(), f.name(), f.mode(),
                c.id(), c.name(),
                p.id(), p.description(), p.weightKg()
                );
            modifyQtyAndUpdateOrder(r, o);
        }
    }

    private void modifyQtyAndUpdateOrder(PlaceOrderRequest r, Order o) {
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

            Order orderOld = mongo.findById(o.id(), Order.class);
            Order nOrder = new Order(o.id(), o.orderNo(), r.customerId(), r.packageId(), (r.quantity()+1), "UPDATED", Instant.now());
            Query query = Query.query(Criteria.where("orderNo").is(o.orderNo()));
            UpdateResult ur = mongo.replace(query,nOrder, ReplaceOptions.replaceOptions().upsert());
            System.out.println("ur: "+ur);
            registry.counter("orders.placed").increment();
        } finally {
            sample.stop(Timer.builder("orders.place").publishPercentileHistogram().register(registry));
        }
    }
}
