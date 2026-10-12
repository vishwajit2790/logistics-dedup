package com.example.logistics.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Pooled (shared, immutable) document: stored once, referenced by id. */
@Document("packages")
public record PackageDoc(@Id String id, String description, Double weightKg, String containerId) {}
