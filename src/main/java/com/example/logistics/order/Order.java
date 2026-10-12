package com.example.logistics.order;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Transactional record: one per purchase. Only REFERENCES the pooled package. */
@Document("orders")
public record Order(
        @Id String id,
        @Indexed(unique = true) String orderNo,
        @Indexed String customerId,
        @Indexed String packageId,      // reference, not a copy
        int quantity,
        String status,
        Instant orderedAt) {}
