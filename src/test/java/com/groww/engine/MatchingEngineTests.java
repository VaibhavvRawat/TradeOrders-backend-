package com.groww.engine;

import com.groww.engine.model.Order;
import com.groww.engine.model.Order.Side;
import com.groww.engine.model.Order.Status;
import com.groww.engine.service.OrderBookService;
import com.groww.engine.service.OrderBookService.SymbolBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Order Matching Engine.
 * Each test uses a unique symbol to avoid state bleed between tests.
 */
@SpringBootTest
class MatchingEngineTests {

    @Autowired
    private OrderBookService service;

    private String sym() {
        return "T" + UUID.randomUUID().toString().replace("-","").substring(0,5).toUpperCase();
    }

    private Order buy(String sym, String id, double price, int qty, long ts) {
        return new Order(id, sym, Side.BUY, price, qty, ts);
    }

    private Order sell(String sym, String id, double price, int qty, long ts) {
        return new Order(id, sym, Side.SELL, price, qty, ts);
    }

    @Test @DisplayName("Context loads")
    void contextLoads() {
        assertNotNull(service);
    }

    @Test @DisplayName("Full fill — equal quantities, 1 trade logged")
    void fullFill() {
        String sym = sym();
        Order b = buy(sym, "B1", 100.0, 10, 1000L);
        Order s = sell(sym, "S1", 100.0, 10, 2000L);
        service.placeOrder(b);
        service.placeOrder(s);

        assertEquals(Status.FILLED, b.getStatus());
        assertEquals(Status.FILLED, s.getStatus());
        assertEquals(1, service.getBook(sym).getTradeLog().size());
    }

    @Test @DisplayName("Partial fill — buy qty > sell qty")
    void partialFill() {
        String sym = sym();
        Order b = buy(sym, "B2", 50.0, 20, 1000L);
        Order s = sell(sym, "S2", 50.0, 5,  2000L);
        service.placeOrder(b);
        service.placeOrder(s);

        assertEquals(Status.PARTIAL, b.getStatus());
        assertEquals(15, b.getRemainingQty());
        assertEquals(Status.FILLED, s.getStatus());
    }

    @Test @DisplayName("No match — spread exists, no trades")
    void noMatch() {
        String sym = sym();
        Order b = buy(sym, "B3", 90.0, 10, 1000L);
        Order s = sell(sym, "S3", 95.0, 10, 2000L);
        service.placeOrder(b);
        service.placeOrder(s);

        assertEquals(Status.OPEN, b.getStatus());
        assertEquals(Status.OPEN, s.getStatus());
        assertTrue(service.getBook(sym).getTradeLog().isEmpty());
    }

    @Test @DisplayName("Price-Time priority — earlier timestamp matches first")
    void priceTimePriority() {
        String sym = sym();
        Order b1 = buy(sym, "B4", 100.0, 5, 1000L); // older  → higher priority
        Order b2 = buy(sym, "B5", 100.0, 5, 2000L); // newer  → lower priority
        service.placeOrder(b1);
        service.placeOrder(b2);
        service.placeOrder(sell(sym, "S4", 100.0, 5, 3000L)); // fills exactly 5 units

        assertEquals(Status.FILLED, b1.getStatus(), "Older buy should match first");
        assertEquals(Status.OPEN,   b2.getStatus(), "Newer buy should stay open");
    }

    @Test @DisplayName("Chain match — one sell drains two resting buys")
    void chainMatch() {
        String sym = sym();
        Order b1 = buy(sym, "B6", 100.0, 5, 1000L);
        Order b2 = buy(sym, "B7",  99.0, 5, 2000L);
        service.placeOrder(b1);
        service.placeOrder(b2);
        service.placeOrder(sell(sym, "S5", 99.0, 10, 3000L));

        assertEquals(Status.FILLED, b1.getStatus());
        assertEquals(Status.FILLED, b2.getStatus());
        assertEquals(2, service.getBook(sym).getTradeLog().size());
    }

    @Test @DisplayName("Duplicate orderId → IllegalArgumentException")
    void duplicateRejected() {
        String sym = sym();
        service.placeOrder(buy(sym, "DUP", 100.0, 5, 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(buy(sym, "DUP", 100.0, 5, 2000L)));
    }
}
