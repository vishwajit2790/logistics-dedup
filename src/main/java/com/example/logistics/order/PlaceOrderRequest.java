package com.example.logistics.order;

/** Describes the package and where it lives in the hierarchy. */
public record PlaceOrderRequest(
        String customerId, int quantity,
        String networkId, String networkName,
        String hubId, String hubName,
        String fleetId, String fleetName, String fleetMode,
        String containerId, String containerName,
        String packageId, String packageDescription, Double weightKg) {}
