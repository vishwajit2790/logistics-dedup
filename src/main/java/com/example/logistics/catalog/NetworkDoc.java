package com.example.logistics.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Pooled (shared, immutable) document: stored once, referenced by id. */
@Document("logistics_networks")
public record NetworkDoc(@Id String id, String name) {}
