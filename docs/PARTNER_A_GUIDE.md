# Partner A Developer Guide — Graph-Analytical Layer

## 1. Overview

Partner A owns the **graph-driven analytical layer** of the fraud detection system. Your code lives in the `org.frauddetection.graph` package inside the Quarkus backend service and is responsible for:

- **Graph truth representation** — building an in-memory directed graph from confirmed transactions.
- **Hypothesis graph** — maintaining a speculative edge layer that predicts future connections and prunes them against reality.
- **Mathematical engine** — computing state projections, interaction energies, fraud hotspot scores, PageRank, and graph drift—all in pure Java with no external math libraries.
- **ML/DL plugin interfaces** — defining contracts for pluggable machine learning models (ensemble heuristics, GNN embeddings, GAN-based anomaly detection).

Everything integrates with the Quarkus CDI container via `@ApplicationScoped` services and is exposed through JAX-RS REST endpoints under `/graph/*`.

---

## 2. Architecture

### Data Flow

```
                        ┌──────────────────────────────────────────────────────┐
  HTTP POST             │              Quarkus Backend (port 8080)             │
  /data/transaction     │                                                      │
 ───────────────────►   │  DataReceiverServlet                                 │
                        │       │                                              │
                        │       ▼                                              │
                        │  FraudDetectionHandler  (ML checks, velocity)        │
                        │       │                                              │
                        │       ▼                                              │
                        │  GraphAnalyticsService.processTransaction(txn)       │
                        │       │                                              │
                        │       ├──► TruthGraph.addTransaction(txn)            │
                        │       │        └── EntityNode.updateState(txn)       │
                        │       │        └── GraphEdge.incrementFlow(amt)      │
                        │       │                                              │
                        │       ├──► HypothesisGraph.predictorStep(txn)        │
                        │       │        └── addSpeculativeEdge(...)           │
                        │       │                                              │
                        │       ├──► HypothesisGraph.correctorStep(truth)      │
                        │       │        └── pruneWeakEdges(threshold)         │
                        │       │                                              │
                        │       └──► GraphMathEngine.fraudHotspotScore(...)    │
                        │              └── Weighted neighbor risk scoring      │
                        │                                                      │
                        │  REST Endpoints (GraphStateEndpoint):                │
                        │    GET  /graph/state        → full graph summary     │
                        │    GET  /graph/metrics      → PageRank, density      │
                        │    GET  /graph/entity/{id}  → single entity detail   │
                        │    POST /graph/reset        → clear all graph state  │
                        └──────────────────────────────────────────────────────┘

  Lit Frontend (port 3000) ──────► submits transactions
  React Dev Preview (port 3001) ─► graph visualization dashboard
```

### Integration Point

`DataReceiverServlet` injects `GraphAnalyticsService` via CDI:

```java
@Inject
GraphAnalyticsService graphAnalyticsService;
```

After the primary fraud detection logic runs, the servlet calls:

```java
JSONObject graphResult = graphAnalyticsService.processTransaction(transactionData);
```

The graph-derived metrics (hotspot score, risk magnitude, hypothesis deviation) are merged into the HTTP response returned to the frontend.

---

## 3. Code Structure

All files live in:
```
backendservices/quarkus-service/src/main/java/org/frauddetection/graph/
```

### EntityNode.java

Account node in the fraud graph. Each node maintains a **5-dimensional state vector** stored as a primitive `double[5]` array for memory efficiency. The five dimensions are: `[txnVolume, avgAmount, riskScore, velocity, diversity]`.

Key methods:
- `updateState(TransactionData txn)` — incrementally recomputes all 5 dimensions from a new transaction. Uses an exponential moving average (α=0.3) for the risk score and tracks unique zip codes for geographic diversity.
- `computeRiskMagnitude()` — returns the L2 (Euclidean) norm of the state vector.
- `getStateVector()` — defensive copy for external consumers.
- `getStateVectorRef()` — direct reference for internal math (package-private).

### GraphEdge.java

Directed edge representing transaction flow between two entities. Stores only aggregated metrics (cumulative weight, transaction count, last transaction time)—no raw transaction data is retained.

Key methods:
- `incrementFlow(double amount)` — atomically adds flow to the edge (synchronized).
- `getDecayedWeight(long currentTime)` — applies exponential time-decay with a 1-hour half-life (`decay = 2^(-elapsed / halfLife)`). Edges that haven't been active recently contribute less to downstream computations.

### TruthGraph.java

`ConcurrentHashMap`-based in-memory directed graph built from confirmed transactions. Nodes are accounts keyed by credit-card number; edges represent observed money flow. All operations are thread-safe.

Key methods:
- `addTransaction(TransactionData txn)` — upserts source and target nodes, creates or updates the connecting edge. Uses `computeIfAbsent` for lazy node creation.
- `findHighRiskNodes(double threshold)` — returns all nodes whose risk magnitude exceeds the threshold, sorted descending.
- `computeGraphDensity()` — `E / (N * (N - 1))` for a directed graph.
- `toSummaryJson()` — compact JSON with node count, edge count, density, and top-5 risk nodes.
- `allNodes()` / `allSourceIds()` — iterators used by the math engine and PageRank.

### HypothesisGraph.java

Speculative edge layer that maintains hypothesized connections not yet confirmed by the truth graph. Implements a **predictor-corrector cycle** for adaptive speculation.

Key methods:
- `predictorStep(TransactionData txn)` — speculates that the source account will transact again with the same merchant (and a perturbed variant), adding low-confidence edges.
- `correctorStep(TruthGraph truth)` — computes deviation from truth and adaptively prunes weak speculations. Higher deviation triggers more aggressive pruning.
- `compareWithTruth(TruthGraph truth)` — fraction of speculative edges not present in the truth graph (0 = perfect, 1 = all wrong).
- `pruneWeakEdges(double minConfidence)` — removes edges below the confidence threshold.

### GraphMathEngine.java

Static utility class containing all mathematical operations. Pure Java, no external libraries. Every method is stateless and operates on primitive arrays.

Key methods:
- `projectState(EntityNode node)` — L2 normalization to unit sphere.
- `interactionEnergy(EntityNode a, EntityNode b)` — dot product of unit-sphere projections; measures behavioral similarity in [-1, 1].
- `deviationNorm(double[] predicted, double[] actual)` — Euclidean distance between two vectors.
- `fraudHotspotScore(TruthGraph graph, long entityId)` — weighted sum of neighbor risk magnitudes using time-decayed edge weights.
- `graphDrift(TruthGraph oldSnapshot, TruthGraph currentGraph)` — average state vector distance between two graph snapshots.
- `computePageRank(TruthGraph graph, int iterations, double damping)` — power-iteration PageRank using array-based dense index mapping for O(1) access.

### GraphAnalyticsService.java

`@ApplicationScoped` CDI service that orchestrates the full graph pipeline. Owns the `TruthGraph` and `HypothesisGraph` instances and is injected into the REST endpoint and the data receiver servlet.

Key methods:
- `processTransaction(TransactionData txn)` — full pipeline: truth update → predictor → corrector → hotspot scoring.
- `getGraphState()` — JSON summary of both graphs.
- `getEntityState(long entityId)` — detailed entity view with state vector breakdown, projected state, neighbor count, and hotspot score.
- `getGraphMetrics()` — graph-wide analytics including density, top risk nodes, and PageRank top-10.
- `resetGraph()` — clears all state (for testing).

### GraphStateEndpoint.java

JAX-RS REST controller at `/graph` that exposes the analytical layer to dashboards and external tools.

Endpoints:
- `GET /graph/state` — full graph state (truth + hypothesis summaries).
- `GET /graph/metrics` — analytical metrics (density, PageRank, top risk nodes).
- `GET /graph/entity/{id}` — detailed state for a single entity.
- `POST /graph/reset` — resets all graph state.

### MLIntegrationInterface.java

Plugin interface for pluggable ML model integrations. Defines three methods that any ML model must implement to integrate with the graph layer.

Methods:
- `predictEntityRisk(EntityNode node)` — returns predicted risk dimensions (same layout as the state vector).
- `predictEdgeStrength(GraphEdge edge)` — predicts likelihood/strength of an edge.
- `updateModel(TruthGraph graph)` — callback for retraining when the truth graph changes.

### EnsembleMLPlugin.java

Default heuristic implementation of `MLIntegrationInterface`. Uses a fixed weight vector `[0.1, 0.2, 0.4, 0.2, 0.1]` to produce weighted-average predictions. This is a stub—replace with a real model (ONNX, PMML, etc.) in production.

Key behavior:
- `predictEntityRisk` — computes a weighted sum of state dimensions and redistributes it.
- `predictEdgeStrength` — returns the time-decayed edge weight.
- `updateModel` — no-op placeholder.

### GNNPluginInterface.java

Future interface for Graph Neural Network integration. Implementations should compute node embeddings by aggregating neighborhood features via message-passing or graph-attention layers.

Methods:
- `computeNodeEmbedding(TruthGraph graph, long entityId)` — single node embedding.
- `computeAllEmbeddings(TruthGraph graph)` — batch embeddings for all nodes.

### GANPluginInterface.java

Future interface for Generative Adversarial Network integration. A GAN plugin generates synthetic edges for data augmentation and provides a discriminator score indicating how "real" an edge appears.

Methods:
- `generateSyntheticEdges(TruthGraph graph, int count)` — produces fake edges mimicking the truth distribution.
- `discriminate(GraphEdge edge, TruthGraph graph)` — probability in [0, 1] that the edge is real.

---

## 4. Theory

### State Vector: 5-Dimensional Entity Representation

Every account in the graph is compressed into a 5-element vector:

| Index | Name        | Meaning                                      |
|-------|-------------|----------------------------------------------|
| 0     | txnVolume   | Total number of transactions                 |
| 1     | avgAmount   | Running average transaction amount           |
| 2     | riskScore   | Exponential moving average of amount ratios  |
| 3     | velocity    | Transactions per hour since first transaction|
| 4     | diversity   | Number of unique zip codes observed          |

This compact representation lets us compare entities, compute distances, and project onto normalized spaces without storing raw transaction histories.

### L2 Normalization (Unit Sphere Projection)

To compare entities fairly regardless of their absolute activity level, we project state vectors onto the **unit sphere**:

```
projected[i] = state[i] / ||state||₂
```

where `||state||₂ = √(Σ state[i]²)`. After projection, every entity lives on the surface of a 5-dimensional sphere with radius 1. This strips away magnitude (how active an entity is) and preserves only the **shape** of their behavior.

### Interaction Energy (Behavioral Similarity)

The **interaction energy** between two entities is the dot product of their unit-sphere projections:

```
energy(A, B) = Σ projectedA[i] × projectedB[i]
```

- **+1** = identical behavioral pattern (same ratios across all dimensions)
- **0** = orthogonal (completely different behavior profiles)
- **-1** = opposite patterns (theoretically possible but rare in practice)

This is equivalent to the cosine similarity between the raw state vectors.

### Fraud Hotspot Score

An entity's **hotspot score** measures how risky its neighborhood is:

```
hotspot(v) = Σ (decayedWeight(v→u) × riskMagnitude(u)) / Σ decayedWeight(v→u)
```

This is a weighted average of neighbor risk magnitudes, where the weight is the time-decayed edge strength. An entity surrounded by high-risk neighbors will inherit a high hotspot score, even if its own state vector looks benign.

### PageRank (Importance Ranking)

PageRank identifies the most structurally important nodes using **power iteration**:

```
rank'[v] = (1 - d)/N + d × Σ (rank[u] / outDegree(u))   for all u → v
```

where `d = 0.85` is the damping factor and `N` is the total node count. The algorithm runs for 20 iterations by default. Nodes that receive many incoming edges from other important nodes rank higher. In fraud detection, high PageRank entities are central hubs in the transaction network.

### Graph Drift

**Graph drift** measures how much the graph structure has changed between two snapshots:

```
drift = (1/count) × Σ ||oldState[v] - currentState[v]||₂
```

Nodes present in only one snapshot receive a fixed penalty of 1.0. A drift value near 0 means the graph is stable; high drift indicates rapid structural evolution (possibly indicating a coordinated attack).

### Predictor-Corrector Cycle

The hypothesis graph uses a two-phase cycle inspired by numerical ODE solvers:

1. **Predictor step** — when a new transaction arrives, speculate that similar future edges will appear (same merchant + perturbed merchants). Add low-confidence speculative edges.
2. **Corrector step** — compare speculative edges against the truth graph. Compute the deviation (fraction of speculations not confirmed). Apply adaptive pruning: `threshold = 0.1 + deviation × 0.5`. Higher deviation means more aggressive pruning.

Over time, the hypothesis graph converges toward patterns that actually occur, while quickly discarding incorrect predictions.

---

## 5. Code Snippets

### Adding a Transaction to the Graph

When `DataReceiverServlet` receives a transaction, it flows through `GraphAnalyticsService.processTransaction()`:

```java
public JSONObject processTransaction(TransactionData txn) {
    long txnNum = transactionCounter.incrementAndGet();

    // 1. Truth graph update
    truthGraph.addTransaction(txn);

    // 2. Predictor: speculate future edges
    hypothesisGraph.predictorStep(txn);

    // 3. Corrector: prune speculations that diverge from truth
    hypothesisGraph.correctorStep(truthGraph);

    // 4. Fraud hotspot score for the source entity
    long sourceId = txn.getCcNum();
    double hotspot = GraphMathEngine.fraudHotspotScore(truthGraph, sourceId);

    // 5. Assemble response
    EntityNode sourceNode = truthGraph.getNode(sourceId);

    JSONObject result = new JSONObject();
    result.put("transactionNumber", txnNum);
    result.put("entityId", sourceId);
    result.put("fraudHotspotScore", hotspot);
    result.put("riskMagnitude", sourceNode != null ? sourceNode.computeRiskMagnitude() : 0.0);
    result.put("graphNodeCount", truthGraph.getNodeCount());
    result.put("graphEdgeCount", truthGraph.getEdgeCount());
    result.put("hypothesisDeviation", hypothesisGraph.compareWithTruth(truthGraph));
    return result;
}
```

Inside `TruthGraph.addTransaction()`, nodes are lazily created with `computeIfAbsent`:

```java
public void addTransaction(TransactionData txn) {
    long sourceId = txn.getCcNum();
    long targetId = deriveMerchantId(txn);

    // Upsert source node and update its state
    EntityNode sourceNode = nodes.computeIfAbsent(sourceId, EntityNode::new);
    sourceNode.updateState(txn);

    // Upsert target node (merchant)
    EntityNode targetNode = nodes.computeIfAbsent(targetId, EntityNode::new);
    targetNode.updateState(txn);

    // Upsert edge and increment flow
    List<GraphEdge> edges = adjacency.computeIfAbsent(sourceId,
            k -> Collections.synchronizedList(new ArrayList<>()));

    GraphEdge edge = findEdge(edges, sourceId, targetId);
    if (edge == null) {
        edge = new GraphEdge(sourceId, targetId);
        edges.add(edge);
        edgeCount.incrementAndGet();
    }
    edge.incrementFlow(txn.getAmt());
}
```

### Querying Entity State

To inspect a single entity's full state via the REST API:

```
GET http://localhost:8080/graph/entity/1234567890
```

The response includes the raw state vector, projected state, neighbor count, and fraud hotspot score:

```java
public JSONObject getEntityState(long entityId) {
    EntityNode node = truthGraph.getNode(entityId);
    JSONObject json = new JSONObject();
    json.put("entityId", entityId);

    if (node == null) {
        json.put("found", false);
        return json;
    }

    json.put("found", true);
    json.put("riskMagnitude", node.computeRiskMagnitude());

    // State vector breakdown
    double[] sv = node.getStateVector();
    JSONObject stateJson = new JSONObject();
    stateJson.put("txnVolume", sv[0]);
    stateJson.put("avgAmount", sv[1]);
    stateJson.put("riskScore", sv[2]);
    stateJson.put("velocity", sv[3]);
    stateJson.put("diversity", sv[4]);
    json.put("stateVector", stateJson);

    // Projected state (unit sphere)
    double[] proj = GraphMathEngine.projectState(node);
    JSONArray projArr = new JSONArray();
    for (double v : proj) {
        projArr.put(v);
    }
    json.put("projectedState", projArr);

    json.put("neighborCount", truthGraph.getNeighbors(entityId).size());
    json.put("fraudHotspotScore", GraphMathEngine.fraudHotspotScore(truthGraph, entityId));
    return json;
}
```

### How the Predictor-Corrector Works

**Predictor** — speculate future edges when a transaction arrives:

```java
public void predictorStep(TransactionData txn) {
    long sourceId = txn.getCcNum();
    long merchantId = TruthGraph.deriveMerchantId(txn);

    // Speculate: source will transact again with this merchant
    double confidence = Math.min(txn.getAmt() / 1000.0, 1.0);
    addSpeculativeEdge(sourceId, merchantId, confidence);

    // Speculate: perturbed merchant id as a proxy for a related merchant
    long perturbedMerchantId = merchantId ^ 0xFFFL;
    addSpeculativeEdge(sourceId, perturbedMerchantId, confidence * 0.3);
}
```

**Corrector** — prune speculations that don't match reality:

```java
public void correctorStep(TruthGraph truth) {
    double deviation = compareWithTruth(truth);

    // Adaptive pruning: higher deviation → more aggressive pruning
    double pruneThreshold = 0.1 + deviation * 0.5;
    pruneWeakEdges(pruneThreshold);
}
```

The `compareWithTruth` method returns the fraction of speculative edges not found in the truth graph. As this fraction grows, the prune threshold increases, removing more weak edges.

### Implementing a Custom ML Plugin

To add your own ML model, implement `MLIntegrationInterface`:

```java
package org.frauddetection.graph;

public class MyCustomMLPlugin implements MLIntegrationInterface {

    @Override
    public double[] predictEntityRisk(EntityNode node) {
        double[] state = node.getStateVectorRef();
        double[] prediction = new double[EntityNode.STATE_DIM];

        // Your model logic here — e.g., call an ONNX runtime,
        // query a remote ML service, or run a local model.
        // The output should match the state vector layout:
        // [txnVolume, avgAmount, riskScore, velocity, diversity]

        return prediction;
    }

    @Override
    public double predictEdgeStrength(GraphEdge edge) {
        // Predict how likely this edge is to represent real activity.
        // Return a value >= 0; higher = more likely.
        return edge.getDecayedWeight(System.currentTimeMillis());
    }

    @Override
    public void updateModel(TruthGraph graph) {
        // Called when the truth graph changes.
        // Use this to retrain your model, refresh feature caches,
        // or pull updated weights from a model registry.
    }
}
```

The existing `EnsembleMLPlugin` uses fixed weights `[0.1, 0.2, 0.4, 0.2, 0.1]` as a baseline heuristic. You can compare your model's predictions against it.

---

## 6. Performance Optimizations

### ConcurrentHashMap for Thread Safety Without Locking

Both `TruthGraph` and `HypothesisGraph` use `ConcurrentHashMap` for their node and adjacency maps. This allows concurrent reads and writes without blocking the entire map—only individual buckets are locked during writes.

```java
private final ConcurrentHashMap<Long, EntityNode> nodes = new ConcurrentHashMap<>();
```

### Primitive `double[]` Instead of Boxed `Double[]`

State vectors are stored as `double[5]` rather than `Double[]` or `List<Double>`. This avoids autoboxing overhead and reduces memory by ~3× (8 bytes per double vs ~24 bytes per boxed Double object).

```java
private final double[] stateVector = new double[STATE_DIM];
```

### Lazy Initialization with `computeIfAbsent`

Nodes are created only when first referenced, avoiding upfront allocation:

```java
EntityNode sourceNode = nodes.computeIfAbsent(sourceId, EntityNode::new);
```

This is both memory-efficient and atomic on `ConcurrentHashMap`.

### Defensive Copy vs Direct Reference

Two getters for the state vector serve different needs:

```java
// Safe for external consumers — returns a clone
public double[] getStateVector() {
    return stateVector.clone();
}

// Fast for internal math — no copy, caller must not mutate
double[] getStateVectorRef() {
    return stateVector;
}
```

`getStateVectorRef()` is package-private, so only code in `org.frauddetection.graph` can use it.

### Synchronized Blocks on Small Adjacency Lists

Edge lists per node are typically small (a few dozen entries), so linear scan with `synchronized` is cheaper than maintaining a concurrent set:

```java
private static GraphEdge findEdge(List<GraphEdge> edges, long sourceId, long targetId) {
    synchronized (edges) {
        for (GraphEdge e : edges) {
            if (e.getSourceId() == sourceId && e.getTargetId() == targetId) {
                return e;
            }
        }
    }
    return null;
}
```

### AtomicLong for Lock-Free Counters

Edge count and transaction count use `AtomicLong` for contention-free increment:

```java
private final AtomicLong edgeCount = new AtomicLong(0);
// ...
edgeCount.incrementAndGet();
```

### StringBuilder for Efficient String Concatenation

`EntityNode.toString()` uses `StringBuilder` instead of string concatenation (`+`) to avoid creating intermediate string objects in a loop:

```java
StringBuilder sb = new StringBuilder("EntityNode{id=");
sb.append(entityId).append(", state=[");
for (int i = 0; i < STATE_DIM; i++) {
    if (i > 0) sb.append(", ");
    sb.append(String.format("%.4f", stateVector[i]));
}
```

### Array-Based PageRank (Dense Index Mapping)

The PageRank implementation maps entity IDs to dense array indices for O(1) access during power iteration, instead of using a `HashMap<Long, Double>` for rank values:

```java
Map<Long, Integer> idToIdx = new HashMap<>(n * 2);
long[] idxToId = new long[n];
double[] rank = new double[n];
```

This avoids per-iteration map lookups and autoboxing during the hot loop.

### Exponential Time-Decay to Avoid Stale Data

Edges use exponential decay with a 1-hour half-life to ensure old, inactive connections don't dominate scoring:

```java
double decay = Math.pow(2.0, -elapsed / DECAY_HALF_LIFE_MS);
return weight * decay;
```

This is computed on-the-fly (not stored), so the raw weight is always preserved for auditing.

---

## 7. Future Research Directions

### Implementing GNNPluginInterface for Message-Passing Embeddings

Implement `GNNPluginInterface` to compute node embeddings that aggregate neighborhood structure. A simple starting point:

```java
public class SimpleGNNPlugin implements GNNPluginInterface {
    @Override
    public double[] computeNodeEmbedding(TruthGraph graph, long entityId) {
        // 1. Gather neighbor state vectors
        // 2. Aggregate via mean/sum/attention
        // 3. Transform with learned weight matrix (or load from ONNX)
        // 4. Return embedding vector
    }
}
```

Use the embeddings as features for a downstream fraud classifier or to enrich the state vector.

### Implementing GANPluginInterface for Synthetic Edge Generation

A GAN plugin can generate synthetic edges to augment training data and improve anomaly detection. The generator learns the truth graph's edge distribution; the discriminator scores how "real" an edge appears. Edges that fool the discriminator but don't exist in the truth graph are prime fraud candidates.

### Adding Explainable AI via State Vector Attribution

Since the state vector has only 5 interpretable dimensions, you can build explainability by showing which dimensions drove a high risk score:

- Compute the gradient of `fraudHotspotScore` with respect to each state dimension.
- Display a breakdown: "Risk driven 40% by velocity, 30% by riskScore, 20% by diversity..."

### Distributed Scaling via Graph Partitioning

For larger deployments, partition the graph across multiple nodes:

- Hash-based partitioning on `entityId` distributes nodes evenly.
- Edge-cut partitioning minimizes cross-partition communication.
- Each partition runs its own `TruthGraph` instance; a coordinator merges PageRank results.

### Connecting to the React Developer Preview

The React-based graph visualizer at port 3001 can consume the REST endpoints directly:

- `GET /graph/state` — renders the full graph topology.
- `GET /graph/metrics` — powers the analytics dashboard.
- `GET /graph/entity/{id}` — drives entity detail panels.

To add new visualizations, expose new data through `GraphStateEndpoint` and consume it in the React app under `graph-visualizer/src/`.

---

## 8. How to Run

### Step 1: Start All Services

From the repository root:

```bash
docker compose up
```

This starts:
- **Quarkus backend** on port 8080
- **Lit frontend** on port 3000
- **React developer preview** (graph visualizer) on port 3001
- **MariaDB** on port 3306
- **Python ML service** on port 5005

### Step 2: Submit Transactions

Open the Lit frontend in your browser:

```
http://localhost:3000
```

Use the transaction form to submit test transactions. Each submission flows through the full pipeline: servlet → fraud detection → graph analytics.

### Step 3: View Graph State

See the full truth graph and hypothesis graph summary:

```
http://localhost:8080/graph/state
```

Example response:
```json
{
  "transactionsProcessed": 42,
  "truthGraph": {
    "nodeCount": 15,
    "edgeCount": 23,
    "density": 0.0109,
    "topRiskNodes": [...]
  },
  "hypothesisGraph": {
    "speculativeEdgeCount": 8,
    "topEdges": [...]
  }
}
```

### Step 4: View Graph Metrics

Get analytical metrics including PageRank and top risk nodes:

```
http://localhost:8080/graph/metrics
```

### Step 5: View Entity Details

Inspect a specific entity by its credit card number:

```
http://localhost:8080/graph/entity/{cc_num}
```

Replace `{cc_num}` with the actual credit card number (the numeric entity ID).

### Step 6: Open the React Developer Preview

For interactive graph visualization:

```
http://localhost:3001
```

### Step 7: Reset Graph State (Development Only)

To clear all graph data and start fresh:

```bash
curl -X POST http://localhost:8080/graph/reset
```

Returns `{"status":"graph reset"}`.
