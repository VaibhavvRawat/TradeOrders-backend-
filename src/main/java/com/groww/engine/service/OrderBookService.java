package com.groww.engine.service;

import com.groww.engine.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory Order Book Service — the heart of the matching engine.
 *
 * <h2>Per-Symbol Data Structures</h2>
 * Each symbol (e.g. "AAPL") gets its own {@link SymbolBook} holding:
 *
 * <pre>
 * // BUY side — Max-Heap: highest price first; ties broken by earliest timestamp
 * PriorityBlockingQueue&lt;Order&gt; buyOrders = new PriorityBlockingQueue&lt;&gt;(16,
 *     Comparator.comparingDouble(Order::getPrice).reversed()
 *               .thenComparingLong(Order::getTimestamp));
 *
 * // SELL side — Min-Heap: lowest price first; ties broken by earliest timestamp
 * PriorityBlockingQueue&lt;Order&gt; sellOrders = new PriorityBlockingQueue&lt;&gt;(16,
 *     Comparator.comparingDouble(Order::getPrice)
 *               .thenComparingLong(Order::getTimestamp));
 * </pre>
 *
 * <h2>Thread-Safety</h2>
 * <ul>
 *   <li>Symbol book creation: {@link ConcurrentHashMap#computeIfAbsent} (atomic)</li>
 *   <li>Matching loop: {@link ReentrantLock} per symbol (serialises the compound
 *       peek → poll → fill → re-enqueue steps that must be atomic together)</li>
 *   <li>Order registry: {@link ConcurrentHashMap} for O(1) status lookup</li>
 *   <li>Trade log: {@link CopyOnWriteArrayList} for lock-free reads</li>
 * </ul>
 */
@Service
public class OrderBookService {

    private static final Logger log = LoggerFactory.getLogger(OrderBookService.class);

    // ── Global order registry (orderId → Order) ───────────────────────────────
    private final ConcurrentHashMap<String, Order> registry = new ConcurrentHashMap<>();

    // ── Per-symbol books (symbol → SymbolBook) ────────────────────────────────
    private final ConcurrentHashMap<String, SymbolBook> books = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Accepts and immediately attempts to match a new order.
     *
     * @throws IllegalArgumentException if the orderId already exists
     */
    public Order placeOrder(Order order) {
        if (registry.putIfAbsent(order.getOrderId(), order) != null) {
            throw new IllegalArgumentException("Duplicate orderId: " + order.getOrderId());
        }
        SymbolBook book = books.computeIfAbsent(order.getSymbol(), SymbolBook::new);
        book.add(order);   // enqueue + match
        return order;
    }

    /** Returns the live SymbolBook snapshot, or {@code null} if unknown symbol. */
    public SymbolBook getBook(String symbol) {
        return books.get(symbol.toUpperCase());
    }

    /** O(1) lookup of any order by ID. */
    public Order findOrder(String orderId) {
        return registry.get(orderId);
    }

    // =========================================================================
    // SymbolBook — per-symbol state + matching logic
    // =========================================================================

    public static class SymbolBook {

        private final String symbol;

        // ── THE KEY DATA STRUCTURES ───────────────────────────────────────────

        /**
         * Buyers: <b>Max-Heap by price</b>.
         * Highest price gets priority; equal prices resolved by earlier timestamp.
         */
        final PriorityBlockingQueue<Order> buyOrders = new PriorityBlockingQueue<>(16,
                Comparator.comparingDouble(Order::getPrice).reversed()
                          .thenComparingLong(Order::getTimestamp));

        /**
         * Sellers: <b>Min-Heap by price</b>.
         * Lowest price gets priority; equal prices resolved by earlier timestamp.
         */
        final PriorityBlockingQueue<Order> sellOrders = new PriorityBlockingQueue<>(16,
                Comparator.comparingDouble(Order::getPrice)
                          .thenComparingLong(Order::getTimestamp));

        /** Immutable trade log — CopyOnWriteArrayList allows lock-free reads. */
        final List<Trade> tradeLog = new CopyOnWriteArrayList<>();

        /**
         * Per-symbol lock that serialises the multi-step matching loop.
         * Without this, two threads could both peek the same best price
         * and produce a phantom double-match.
         */
        private final ReentrantLock matchLock = new ReentrantLock(/* fair= */ true);
        private final AtomicLong tradeSeq = new AtomicLong(0);

        private static final Logger log = LoggerFactory.getLogger(SymbolBook.class);

        public SymbolBook(String symbol) {
            this.symbol = symbol;
        }

        /** Enqueues the order on the correct side, then runs the matching loop. */
        void add(Order order) {
            if (order.getSide() == Order.Side.BUY) {
                buyOrders.offer(order);
                log.info("[{}] BUY  queued  → {}", symbol, order);
            } else {
                sellOrders.offer(order);
                log.info("[{}] SELL queued  → {}", symbol, order);
            }
            match();
        }

        /**
         * Price-Time Priority matching loop.
         *
         * <p>A trade fires when {@code bestBid.price >= bestAsk.price}.
         * Execution price = the resting (older) order's price, which is the
         * standard limit-order book convention.
         */
        private void match() {
            matchLock.lock();
            try {
                while (!buyOrders.isEmpty() && !sellOrders.isEmpty()) {

                    Order bid = buyOrders.peek();   // best buy  (highest price)
                    Order ask = sellOrders.peek();  // best sell (lowest price)

                    if (bid == null || ask == null || bid.getPrice() < ask.getPrice()) {
                        break;  // spread exists — no match possible
                    }

                    // Dequeue both sides
                    bid = buyOrders.poll();
                    ask = sellOrders.poll();

                    // Executable quantity = the smaller of the two remaining quantities
                    int qty = Math.min(bid.getRemainingQty(), ask.getRemainingQty());

                    // Execution price: resting order (earlier timestamp) sets the price
                    double execPrice = (bid.getTimestamp() < ask.getTimestamp())
                                       ? bid.getPrice() : ask.getPrice();

                    bid.fill(qty);
                    ask.fill(qty);

                    Trade trade = new Trade(
                            symbol + "-T" + tradeSeq.incrementAndGet(),
                            symbol, bid.getOrderId(), ask.getOrderId(), execPrice, qty);

                    tradeLog.add(trade);
                    log.info("TRADE ✓ {} BUY#{} ↔ SELL#{} | {}u @ {}", 
                             symbol, bid.getOrderId(), ask.getOrderId(), qty, execPrice);

                    // Re-queue any unfilled remainder
                    if (!bid.isFilled()) buyOrders.offer(bid);
                    if (!ask.isFilled()) sellOrders.offer(ask);
                }
            } finally {
                matchLock.unlock();
            }
        }

        // ── Snapshot helpers (called by the REST layer) ───────────────────────

        /** Sorted snapshot of all resting buy orders, best price first. */
        public List<Order> getBids() {
            List<Order> snap = new ArrayList<>(buyOrders);
            snap.sort(Comparator.comparingDouble(Order::getPrice).reversed()
                                .thenComparingLong(Order::getTimestamp));
            return Collections.unmodifiableList(snap);
        }

        /** Sorted snapshot of all resting sell orders, best price first. */
        public List<Order> getAsks() {
            List<Order> snap = new ArrayList<>(sellOrders);
            snap.sort(Comparator.comparingDouble(Order::getPrice)
                                .thenComparingLong(Order::getTimestamp));
            return Collections.unmodifiableList(snap);
        }

        public String      getSymbol()   { return symbol; }
        public List<Trade> getTradeLog() { return Collections.unmodifiableList(tradeLog); }
    }

    // =========================================================================
    // Trade — immutable record of an executed match
    // =========================================================================

    public record Trade(
            String tradeId,
            String symbol,
            String buyOrderId,
            String sellOrderId,
            double executionPrice,
            int    executedQty
    ) {}
}
