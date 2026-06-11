package com.groww.engine.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a limit order in the matching engine.
 *
 * <p>Key design choices:
 * <ul>
 *   <li>{@code remainingQty} is an {@link AtomicInteger} so it can be safely
 *       read from outside the matching lock while being written inside it.</li>
 *   <li>{@code status} is {@code volatile} so the latest fill state is always
 *       visible across threads without additional synchronisation.</li>
 *   <li>All identity fields are {@code final} — orders are immutable except
 *       for their fill state.</li>
 * </ul>
 */
public class Order {

    // ── Inner enums (kept in one file to reduce clutter) ─────────────────────

    public enum Side   { BUY, SELL }
    public enum Status { OPEN, PARTIAL, FILLED }

    // ── Identity fields (immutable) ───────────────────────────────────────────

    private final String  orderId;
    private final String  symbol;
    private final Side    side;
    private final double  price;
    private final int     quantity;

    /**
     * Epoch-millisecond timestamp — used as the tiebreaker inside the
     * PriorityBlockingQueues when two orders share the same price.
     * Smaller value = placed earlier = higher priority.
     */
    private final long    timestamp;

    // ── Mutable fill state ────────────────────────────────────────────────────

    private final AtomicInteger remainingQty;
    private volatile Status     status;

    // ── Constructor ───────────────────────────────────────────────────────────

    public Order(String orderId, String symbol, Side side,
                 double price, int quantity, long timestamp) {
        this.orderId      = orderId;
        this.symbol       = symbol.toUpperCase();
        this.side         = side;
        this.price        = price;
        this.quantity     = quantity;
        this.timestamp    = timestamp;
        this.remainingQty = new AtomicInteger(quantity);
        this.status       = Status.OPEN;
    }

    // ── Domain logic ──────────────────────────────────────────────────────────

    /**
     * Reduces remaining quantity and advances status.
     * Must be called inside the per-symbol {@code ReentrantLock}.
     */
    public void fill(int amount) {
        int left = remainingQty.addAndGet(-amount);
        status   = (left == 0) ? Status.FILLED : Status.PARTIAL;
    }

    public boolean isFilled() { return remainingQty.get() == 0; }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getOrderId()      { return orderId; }
    public String  getSymbol()       { return symbol; }
    public Side    getSide()         { return side; }
    public double  getPrice()        { return price; }
    public int     getQuantity()     { return quantity; }
    public long    getTimestamp()    { return timestamp; }
    public int     getRemainingQty() { return remainingQty.get(); }
    public Status  getStatus()       { return status; }

    @Override
    public String toString() {
        return String.format("[%s] %s %s qty=%d rem=%d @ %.2f ts=%d",
                orderId, side, symbol, quantity, remainingQty.get(), price, timestamp);
    }
}
