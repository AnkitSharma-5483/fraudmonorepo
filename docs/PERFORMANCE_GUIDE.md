# Performance Guide — Java Graph Engine

Practical performance optimizations used in the `org.frauddetection.graph` package, explained for beginners.

---

## 1. Data Structures

### ConcurrentHashMap vs HashMap

The graph stores nodes and edges in maps that are accessed from multiple threads simultaneously (every incoming transaction triggers a graph update). A regular `HashMap` is **not thread-safe** — concurrent writes can corrupt it silently.

```java
// BAD: Will corrupt under concurrent access
private final HashMap<Long, EntityNode> nodes = new HashMap<>();

// GOOD: Thread-safe without locking the entire map
private final ConcurrentHashMap<Long, EntityNode> nodes = new ConcurrentHashMap<>();
```

**Why ConcurrentHashMap?** It partitions the internal table into segments. Multiple threads can read and write to *different* segments simultaneously. Only writes to the *same* segment need to synchronize. For our use case (many entity IDs spread across the map), contention is minimal.

**When to use which:**
- `HashMap` — single-threaded code, local variables inside a method
- `ConcurrentHashMap` — shared state accessed by multiple request threads

### Primitive `double[]` vs `Double[]`

Each entity's state vector is stored as a primitive `double[5]` array instead of a boxed `Double[]` or `List<Double>`.

```java
// BAD: Each Double object is ~24 bytes on the heap (object header + padding)
private final Double[] stateVector = new Double[5];   // ~120 bytes

// GOOD: Each double is exactly 8 bytes, packed contiguously in memory
private final double[] stateVector = new double[5];    // ~40 bytes (+ array header)
```

**Memory savings**: ~3× less memory per node. With thousands of nodes, this adds up. Primitive arrays also avoid garbage collection pressure from autoboxing.

### AtomicLong for Lock-Free Counting

Simple counters (edge count, transaction count) use `AtomicLong` instead of `synchronized` blocks:

```java
// BAD: synchronized block for a single counter is heavy
private long edgeCount = 0;
public synchronized void increment() { edgeCount++; }

// GOOD: Lock-free CAS (compare-and-swap) under the hood
private final AtomicLong edgeCount = new AtomicLong(0);
edgeCount.incrementAndGet();  // atomic, no lock needed
```

`AtomicLong` uses CPU-level compare-and-swap instructions — no thread ever blocks waiting for a lock.

### Collections.synchronizedList for Small Lists

Each node's adjacency list (outgoing edges) is typically small — a few dozen edges at most. For small lists, a synchronized `ArrayList` is simpler and faster than a `ConcurrentLinkedQueue`:

```java
List<GraphEdge> edges = adjacency.computeIfAbsent(
    sourceId,
    k -> Collections.synchronizedList(new ArrayList<>())
);
```

Linear scan through a small synchronized list is cheaper than maintaining a concurrent skip-list or hash set, because the constant factors are lower and cache locality is better.

---

## 2. Memory Management

### Lazy Initialization with `computeIfAbsent`

Nodes are created only when first referenced, not pre-allocated:

```java
// Creates the node only if sourceId doesn't already exist in the map
EntityNode sourceNode = nodes.computeIfAbsent(sourceId, EntityNode::new);
```

**Why this matters:**
- No upfront allocation for entity IDs that never appear.
- The operation is atomic on `ConcurrentHashMap` — no risk of creating duplicate nodes.
- `EntityNode::new` is a method reference (constructor), so no lambda object is allocated if the key exists.

### No Raw Transaction Duplication

The graph never stores full transaction objects. Instead, each transaction is *reduced* into incremental state updates:

```java
public synchronized void updateState(TransactionData txn) {
    stateVector[0] += 1;                                           // txnVolume
    stateVector[1] = ((stateVector[1] * (stateVector[0]-1)) + txn.getAmt()) / stateVector[0]; // avgAmount
    stateVector[2] = 0.7 * stateVector[2] + 0.3 * (txn.getAmt() / 1000.0);                   // riskScore (EMA)
    // ... velocity and diversity updates
}
```

After `updateState()` returns, the `TransactionData` object can be garbage collected. The graph retains only the 5-element state vector per node and the edge weight per edge.

### Defensive Copy vs Direct Reference

Two getters serve different performance needs:

```java
// For external consumers (REST API, tests) — safe but allocates
public double[] getStateVector() {
    return stateVector.clone();  // caller gets their own copy
}

// For internal math (GraphMathEngine) — fast, zero allocation
double[] getStateVectorRef() {
    return stateVector;  // caller must NOT mutate this
}
```

`getStateVectorRef()` is package-private, so only code in `org.frauddetection.graph` can use it. This avoids cloning arrays thousands of times during PageRank computation.

---

## 3. Computation

### Array-Based PageRank with Dense Index Mapping

Entity IDs are sparse `long` values (credit card numbers). Looking them up in a `HashMap` on every iteration is slow. Instead, we map them to dense array indices:

```java
// Build dense index mapping
Map<Long, Integer> idToIdx = new HashMap<>(n * 2);  // pre-sized
long[] idxToId = new long[n];
double[] rank = new double[n];   // O(1) access by index
double[] newRank = new double[n];

int idx = 0;
for (Long id : graph.allSourceIds()) {
    idToIdx.put(id, idx);
    idxToId[idx] = id;
    rank[idx] = 1.0 / n;
    idx++;
}
```

**During power iteration**, all lookups are array accesses (`rank[i]`) instead of hash map gets. This avoids:
- Autoboxing `int` → `Integer` for map keys
- Hash computation on every access
- Pointer chasing through map buckets

The result: PageRank runs in `O(iterations × (N + E))` with very low constant factors.

### Exponential Time-Decay for Natural Data Aging

Edge weights decay over time so stale connections don't dominate risk scores:

```java
private static final double DECAY_HALF_LIFE_MS = 3_600_000; // 1 hour

public double getDecayedWeight(long currentTime) {
    double elapsed = currentTime - lastTransactionTime;
    double decay = Math.pow(2.0, -elapsed / DECAY_HALF_LIFE_MS);
    return weight * decay;
}
```

**How it works:**
- After 1 hour, the weight is halved.
- After 2 hours, it's quartered.
- After 10 hours, it's ~0.1% of the original.

The raw `weight` is never modified — decay is computed on-the-fly. This means the original data is always preserved for auditing.

### StringBuilder vs String Concatenation

When building strings in a loop, `StringBuilder` avoids creating intermediate `String` objects:

```java
// BAD: Creates a new String object on every iteration
String result = "";
for (int i = 0; i < STATE_DIM; i++) {
    result += stateVector[i] + ", ";  // N allocations
}

// GOOD: One mutable buffer, no intermediate objects
StringBuilder sb = new StringBuilder("EntityNode{id=");
sb.append(entityId).append(", state=[");
for (int i = 0; i < STATE_DIM; i++) {
    if (i > 0) sb.append(", ");
    sb.append(String.format("%.4f", stateVector[i]));
}
sb.append("]}");
return sb.toString();  // one allocation at the end
```

---

## 4. Thread Safety

### @ApplicationScoped Singleton for Graph Service

`GraphAnalyticsService` is a CDI singleton — one instance handles all requests:

```java
@ApplicationScoped
public class GraphAnalyticsService {
    private final TruthGraph truthGraph = new TruthGraph();
    private final HypothesisGraph hypothesisGraph = new HypothesisGraph();
    private final AtomicLong transactionCounter = new AtomicLong(0);
}
```

**Why singleton?** The in-memory graph must be shared across all request threads. Creating a new graph per request would lose all accumulated state. The `@ApplicationScoped` annotation tells Quarkus to create exactly one instance for the entire application lifecycle.

### Synchronized Blocks on Per-Node Adjacency Lists

Edge lookups synchronize only on the specific node's edge list, not the entire graph:

```java
private static GraphEdge findEdge(List<GraphEdge> edges, long sourceId, long targetId) {
    synchronized (edges) {              // lock ONLY this node's edge list
        for (GraphEdge e : edges) {
            if (e.getSourceId() == sourceId && e.getTargetId() == targetId) {
                return e;
            }
        }
    }
    return null;
}
```

Two transactions involving *different* source accounts proceed in parallel — they lock different lists. Only transactions from the *same* account serialize, which is the correct behavior anyway (you want to process one card's transactions in order).

### ConcurrentHashMap for Node/Edge Maps

The top-level node and adjacency maps use `ConcurrentHashMap`:

```java
private final ConcurrentHashMap<Long, EntityNode> nodes = new ConcurrentHashMap<>();
private final ConcurrentHashMap<Long, List<GraphEdge>> adjacency = new ConcurrentHashMap<>();
```

This gives us:
- **Lock-free reads**: `getNode()` never blocks, even during writes.
- **Segment-level writes**: Two threads adding different nodes don't contend.
- **Atomic `computeIfAbsent`**: Node creation is safe without external synchronization.

---

## 5. What to Add Later

These optimizations aren't needed yet, but are worth considering as the graph grows:

### Object Pooling for EntityNode / GraphEdge

If the graph processes millions of transactions per hour, object creation pressure may become significant. An object pool reuses `EntityNode` and `GraphEdge` instances instead of allocating new ones:

```java
// Concept — not implemented yet
EntityNode node = nodePool.acquire(entityId);
// ... use node ...
// Don't release — nodes are long-lived. Pool edges that get pruned instead.
```

**When to add**: If GC pause times exceed your latency budget (check with `-Xlog:gc`).

### Off-Heap Memory for Very Large Graphs

For graphs with millions of nodes, the JVM heap may not be large enough. Off-heap storage (e.g., `ByteBuffer.allocateDirect()` or libraries like Chronicle Map) keeps graph data outside the GC-managed heap:

```java
// Concept — store state vectors off-heap
ByteBuffer buffer = ByteBuffer.allocateDirect(nodeCount * STATE_DIM * 8);
```

**When to add**: When heap usage consistently exceeds 4–8 GB and GC pauses are problematic.

### Bloom Filters for Edge Existence Checks

Before scanning an adjacency list to check if an edge exists, a Bloom filter can give a fast "definitely not present" answer:

```java
// Concept — skip list scan if Bloom filter says no
if (!bloomFilter.mightContain(edgeKey)) {
    return null;  // definitely not present — no list scan needed
}
// Bloom filter says maybe — scan the list to confirm
```

**When to add**: When adjacency lists grow beyond ~100 edges per node and edge lookups become a bottleneck.

### Graph Partitioning for Distributed Processing

For very large deployments, partition the graph across multiple JVM instances:

- **Hash partitioning**: `entityId % numPartitions` distributes nodes evenly.
- **Edge-cut partitioning**: Minimizes cross-partition edges for better locality.
- Each partition runs its own `TruthGraph`; a coordinator merges PageRank and risk scores.

**When to add**: When a single JVM can no longer hold the full graph in memory (typically millions of active nodes).

---

## 6. What NOT to Do

Common anti-patterns that will hurt performance or correctness:

### Don't Synchronize the Entire Graph for One Transaction

```java
// BAD: Blocks ALL transactions while one is being processed
public synchronized JSONObject processTransaction(TransactionData txn) {
    truthGraph.addTransaction(txn);
    // ... everything waits for this one transaction
}

// GOOD: Fine-grained locking inside TruthGraph (per-node synchronization)
public JSONObject processTransaction(TransactionData txn) {
    truthGraph.addTransaction(txn);  // only locks the specific node's edge list
    // ... other transactions on different nodes proceed in parallel
}
```

The current design uses `ConcurrentHashMap` + per-list `synchronized` blocks so that transactions on different entities don't block each other.

### Don't Store Raw JSON in the Graph

```java
// BAD: Stores full JSON string per transaction — memory blows up
class EntityNode {
    List<String> rawTransactions = new ArrayList<>();  // unbounded growth
}

// GOOD: Reduce each transaction to a fixed-size state update
class EntityNode {
    double[] stateVector = new double[5];  // constant 40 bytes regardless of history
}
```

The state vector is an *aggregation* — it captures the statistical profile of all transactions without storing any of them. This is why the graph can process millions of transactions without running out of memory.

### Don't Create a New Connection Per Graph Query

```java
// BAD: Opens and closes a DB connection for every graph operation
public void addTransaction(TransactionData txn) {
    Connection conn = DriverManager.getConnection(url, user, pass);  // expensive!
    // ... use conn ...
    conn.close();
}

// GOOD: Let CDI manage the service lifecycle — inject once, reuse everywhere
@Inject
GraphAnalyticsService graphAnalyticsService;  // singleton, created once

// The graph is in-memory, so no DB connection is needed for graph operations.
// DB access for storing transactions uses the Quarkus-managed datasource.
```

`GraphAnalyticsService` is `@ApplicationScoped` — Quarkus creates one instance and injects it wherever needed. The in-memory graph has zero database overhead for reads. Database writes (storing transactions, logging fraud) go through the Quarkus-managed connection pool, not manually opened connections.
