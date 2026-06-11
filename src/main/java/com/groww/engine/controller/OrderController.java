package com.groww.engine.controller;

import com.groww.engine.model.Order;
import com.groww.engine.service.OrderBookService;
import com.groww.engine.service.OrderBookService.SymbolBook;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * REST controller exposing the Order Matching Engine API.
 *
 * <pre>
 * POST /api/orders              — Submit a new limit order
 * GET  /api/orderbook/{symbol}  — Live order book depth
 * GET  /api/orders/{orderId}    — Single order status
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderBookService service;

    public OrderController(OrderBookService service) {
        this.service = service;
    }

    // ── POST /api/orders ──────────────────────────────────────────────────────

    /**
     * Submit a limit order. The engine attempts to match it immediately.
     *
     * <p>Example request body:
     * <pre>
     * {
     *   "orderId":   "ORD-001",
     *   "symbol":    "AAPL",
     *   "side":      "BUY",
     *   "price":     150.00,
     *   "quantity":  100
     * }
     * </pre>
     */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> placeOrder(
            @Valid @RequestBody OrderRequest req) {

        try {
            long ts = (req.timestamp() > 0) ? req.timestamp() : Instant.now().toEpochMilli();
            Order order = new Order(req.orderId(), req.symbol(), req.side(),
                                    req.price(), req.quantity(), ts);
            service.placeOrder(order);
            return ResponseEntity.status(HttpStatus.CREATED).body(toMap(order));

        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    // ── GET /api/orderbook/{symbol} ───────────────────────────────────────────

    /**
     * Returns the live order book — resting bids (highest first) and
     * asks (lowest first) — plus the executed trade log.
     */
    @GetMapping("/orderbook/{symbol}")
    public ResponseEntity<Map<String, Object>> getOrderBook(
            @PathVariable String symbol) {

        SymbolBook book = service.getBook(symbol.toUpperCase());
        if (book == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No order book found for: " + symbol.toUpperCase()));
        }

        return ResponseEntity.ok(Map.of(
                "symbol",     book.getSymbol(),
                "bids",       book.getBids().stream().map(this::toMap).toList(),
                "asks",       book.getAsks().stream().map(this::toMap).toList(),
                "tradeCount", book.getTradeLog().size(),
                "trades",     book.getTradeLog()
        ));
    }

    // ── GET /api/orders/{orderId} ─────────────────────────────────────────────

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        Order order = service.findOrder(orderId);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Order not found: " + orderId));
        }
        return ResponseEntity.ok(toMap(order));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(Order o) {
        return Map.of(
                "orderId",      o.getOrderId(),
                "symbol",       o.getSymbol(),
                "side",         o.getSide().name(),
                "price",        o.getPrice(),
                "quantity",     o.getQuantity(),
                "remainingQty", o.getRemainingQty(),
                "status",       o.getStatus().name(),
                "timestamp",    o.getTimestamp()
        );
    }

    // ── Inbound DTO (Java record with Bean Validation) ────────────────────────

    public record OrderRequest(

            @NotBlank(message = "orderId is required")
            String orderId,

            @NotBlank @Pattern(regexp = "[A-Za-z]{1,10}",
                               message = "symbol must be 1-10 letters")
            String symbol,

            @NotNull(message = "side must be BUY or SELL")
            Order.Side side,

            @Positive(message = "price must be positive")
            double price,

            @Min(value = 1, message = "quantity must be >= 1")
            int quantity,

            long timestamp   // optional; 0 = use server time
    ) {}
}
