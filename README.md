logistics-dedup
Spring Boot service that stores a 5-level logistics hierarchy
(`LogisticsNetwork > RegionalHub > TransportFleet > ShippingContainer > Package`)
once (object pool / flyweight) and records every purchase as a lightweight `Order`
that only references the shared package. Redis caches pooled lookups; MongoDB is the source of truth.
Requirements
Java 17+, Maven 3.9+, Docker. (Optional: `mongosh` for the seed script.)
Generate the Maven wrapper once with `mvn wrapper:wrapper` if you want `./mvnw`.
Quick start
```bash
docker compose up -d        # MongoDB + Redis
mvn spring-boot:run         # profile "local" is the default
```
Then open `http/orders.http` in IntelliJ or VS Code (REST Client) and send Place an order twice:
you get 2 orders but only one document per hierarchy level.
Project structure
```
logistics-dedup/
├── pom.xml
├── Dockerfile
├── docker-compose.yml          # infra; --profile monitoring adds Prometheus/Grafana
├── Makefile                    # shortcuts: infra, monitoring, run, test, verify, seed
├── http/orders.http            # ready-made requests (IntelliJ / VS Code)
├── scripts/mongo/              # seed_logistics.js (sample data)
├── monitoring/                 # prometheus.yml, alerts.yml, Grafana provisioning + dashboard
├── .run/                       # shared IntelliJ run configs
├── .vscode/                    # shared VS Code launch/settings/extensions
└── src/
    ├── main/java/com/example/logistics/
    │   ├── LogisticsApplication.java
    │   ├── config/             # CacheProperties (app.cache.*)
    │   ├── catalog/            # pooled, immutable documents (one file per level)
    │   ├── pool/               # DocumentPool (get-or-create), CatalogCache (read-through)
    │   └── order/              # Order, PlaceOrderRequest, OrderService, OrderController
    ├── main/resources/         # application.properties, application-local.properties
    └── test/java/com/example/logistics/
        ├── support/            # AbstractIntegrationTest (Testcontainers Mongo + Redis)
        ├── pool/               # DocumentPoolTest      (unit, mocked)
        └── order/              # OrderDeduplicationIT, OrderApiIT (integration)
```
Tests
Command	Runs	Needs Docker
`mvn test`	`*Test` unit tests	no
`mvn verify`	unit + `*IT` integration tests	yes
Monitoring
```bash
docker compose --profile monitoring up -d
```
Prometheus http://localhost:9090 · Grafana http://localhost:3000 (dashboard "Logistics Dedup").
App endpoints: `/actuator/health`, `/actuator/health/readiness`, `/actuator/prometheus`.
Custom metrics: `pool.ensure{type,result}`, `catalog.cache{type,result}`,
`redis.fallback{op}`, `orders.placed`, `orders.place` (latency histogram).
Design notes
Duplicates are prevented by MongoDB (atomic `upsert` + `$setOnInsert` on a unique `_id`), not by Redis.
Redis only skips work; if it is empty, evicted, or down the app falls back to MongoDB.
Pooled documents are immutable. Create a new versioned id instead of editing in place.
Orders are transactional and never cached.
