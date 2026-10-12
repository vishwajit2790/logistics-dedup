package com.example.logistics.order;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @PostMapping
    public Order place(@RequestBody PlaceOrderRequest req) { return service.placeOrder(req); }

    @GetMapping("/{orderNo}/trace")
    public Map<String, Object> trace(@PathVariable String orderNo) { return service.trace(orderNo); }
}
