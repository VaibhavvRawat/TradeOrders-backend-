# Order It Up ⚡ — In-Memory Order Matching Engine

> **Senior Fintech Engineering Project** — A lightweight, highly reliable, thread-safe In-Memory Order Matching Engine built with Java 17 + Spring Boot 3.

---

## Architecture at a Glance

```
REST Controller
     │
     ▼
OrderService (validation, DTO→Domain mapping)
     │
     ▼
OrderBookManager (ConcurrentHashMap<symbol, OrderBook>)
     │
     ▼
OrderBook (per symbol)
  ├── PriorityBlockingQueue<Order>  buyOrders  [MAX-HEAP by price, then timestamp]
  ├── PriorityBlockingQueue<Order>  sellOrders [MIN-HEAP by price, then timestamp]
  ├── ReentrantLock                 matchLock  [serialises compound match operations]
  └── CopyOnWriteArrayList<Trade>   tradeLog   [lock-free reads]
```

---

## Key Data Structures

```java
// Buyers: Highest price gets priority. If prices match, older timestamp wins.
PriorityBlockingQueue<Order> buyOrders = new PriorityBlockingQueue<>(11,
    Comparator.comparingDouble(Order::getPrice).reversed()
              .thenComparingLong(Order::getTimestamp));

// Sellers: Lowest price gets priority. If prices match, older timestamp wins.
PriorityBlockingQueue<Order> sellOrders = new PriorityBlockingQueue<>(11,
    Comparator.comparingDouble(Order::getPrice)
              .thenComparingLong(Order::getTimestamp));
```

---

## REST API

### `POST /api/orders` — Submit a new order

```json
{
  "orderId":   "ORD-001",
  "symbol":    "AAPL",
  "side":      "BUY",
  "price":     150.00,
  "quantity":  100,
  "timestamp": 1700000000000
}
```

**Response `201 Created`:**
```json
{
  "message":      "Order accepted",
  "orderId":      "ORD-001",
  "symbol":       "AAPL",
  "side":         "BUY",
  "price":        150.0,
  "quantity":     100,
  "remainingQty": 0,
  "status":       "FILLED",
  "timestamp":    1700000000000
}
```

---

### `GET /api/orderbook/{symbol}` — Live order book

```bash
GET /api/orderbook/AAPL
```

Returns the full bid/ask depth with best bid price, best ask price, and current spread.

---

### `GET /api/trades/{symbol}` — Executed trade history

```bash
GET /api/trades/AAPL
```

---

### `GET /api/orders/{orderId}` — Order status lookup

```bash
GET /api/orders/ORD-001
```

---

## Running Locally

### Prerequisites
- Java 17 or 21
- Maven 3.9+

### Start the server

```bash
./mvnw spring-boot:run
# Server starts at http://localhost:8080
```

### Run tests

```bash
./mvnw test
```

---

## Matching Algorithm — Price-Time Priority

1. **BUY order arrives** → placed in the max-heap buy queue
2. **Matching loop starts** → peek at `bestBid` and `bestAsk`
3. **Match condition**: `bestBid.price >= bestAsk.price`
4. **Execution price**: the resting order's price (the one already in the queue)
5. **Executable qty**: `min(buyRemaining, sellRemaining)`
6. **Fills applied** → statuses updated (`OPEN → PARTIAL → FILLED`)
7. **Partial remainder re-enqueued** → loop continues until no match possible
8. **Trade logged** → console + in-memory trade log

---

## Thread-Safety Guarantees

| Component | Mechanism |
|---|---|
| Order submission | `ConcurrentHashMap.computeIfAbsent` — atomic book creation |
| Queue operations | `PriorityBlockingQueue` — internally lock-protected |
| Matching loop | `ReentrantLock(fair=true)` — serialises multi-step compound ops |
| Trade log reads | `CopyOnWriteArrayList` — snapshot isolation without locks |
| Order status reads | `volatile OrderStatus` + `AtomicInteger remainingQty` |

---

## Project Structure

```
src/main/java/com/fintech/orderengine/
├── OrderMatchingEngineApplication.java
├── model/
│   ├── Order.java
│   ├── OrderSide.java
│   ├── OrderStatus.java
│   └── Trade.java
├── engine/
│   ├── OrderBook.java          ← Core matching logic
│   └── OrderBookManager.java   ← Symbol registry
├── service/
│   └── OrderService.java
├── controller/
│   └── OrderController.java
└── dto/
    ├── OrderRequest.java
    └── OrderBookResponse.java
```
