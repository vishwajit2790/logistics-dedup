.PHONY: infra monitoring run test verify seed down

infra:        ## MongoDB + Redis
	docker compose up -d

monitoring:   ## + Prometheus, Grafana, exporters
	docker compose --profile monitoring up -d

run:          ## start the app with the local profile
	mvn spring-boot:run -Dspring-boot.run.profiles=local

test:         ## unit tests only (no Docker needed)
	mvn test

verify:       ## unit + integration tests (Docker required)
	mvn verify

seed:         ## load sample 5-level hierarchy into local MongoDB
	mongosh "mongodb://localhost:27017" scripts/mongo/seed_logistics.js

down:
	docker compose --profile monitoring down
