package com.example.logistics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from app.cache.* (autocompleted in application.properties via the configuration processor). */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
        /** How long pooled documents / existence markers live in Redis. They are immutable, so long is safe. */
        @DefaultValue("24h") Duration ttl) {}
