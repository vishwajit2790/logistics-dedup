package com.example.logistics.order;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService service;
    private final OrderServiceV2 serviceV2;
    public OrderController(OrderService service, OrderServiceV2 serviceV2) { this.service = service;
        this.serviceV2 = serviceV2;
    }

    @PostMapping
    public Order place(@RequestBody PlaceOrderRequest req) { return service.placeOrder(req); }

    @GetMapping("/{orderNo}/trace")
    public Map<String, Object> trace(@PathVariable String orderNo) { return service.trace(orderNo); }

    @PostMapping("/v2")
    public ResponseEntity<String> updateQtys(){
        serviceV2.updateQtys();
        return ResponseEntity.ok("Quantities updated");
    }

    @PostMapping("/v2/batch")
    public ResponseEntity<String> updateQtysByBatch(){
        serviceV2.updateOrdersByBatch();
        return ResponseEntity.ok("Quantities updated");
    }
    @GetMapping("/{orderNo}/trace/v2")
    public Map<String, Object> traceV2(@PathVariable String orderNo) { return serviceV2.trace(orderNo); }
}
