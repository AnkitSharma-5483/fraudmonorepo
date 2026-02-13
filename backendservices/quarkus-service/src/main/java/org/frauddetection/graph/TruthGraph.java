package org.frauddetection.graph;

import org.frauddetection.model.TransactionData;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compressed in-memory "truth" graph built from confirmed transactions.
 * <p>
 * Nodes are accounts (keyed by credit-card number) and edges represent observed
 * money flow.  Only aggregated state is stored — no raw transaction duplication.
 * All operations are thread-safe via {@link ConcurrentHashMap}.
 */
public class TruthGraph {

    /** Node map: entityId → EntityNode. */
    private final ConcurrentHashMap<Long, EntityNode> nodes = new ConcurrentHashMap<>();

    /** Adjacency list: sourceId → list of outgoing edges. */
    private final ConcurrentHashMap<Long, List<GraphEdge>> adjacency = new ConcurrentHashMap<>();

    /** Running count of total edges for O(1) lookup. */
    private final AtomicLong edgeCount = new AtomicLong(0);

    // ---- Graph mutation ----

    /**
     * Ingests a confirmed transaction, creating or updating the source node and
     * the edge from source to a deterministic merchant-derived target id.
     */
    public void addTransaction(TransactionData txn) {
        long sourceId = txn.getCcNum();

        // Derive a stable target id from merchant coordinates (simple hash)
        long targetId = deriveMerchantId(txn);

        // Upsert source node and update its state
        EntityNode sourceNode = nodes.computeIfAbsent(sourceId, EntityNode::new);
        sourceNode.updateState(txn);

        // Upsert target node (merchant) — also receives the transaction signal
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

    // ---- Queries ----

    /** Returns the node for the given entity, or {@code null} if absent. */
    public EntityNode getNode(long entityId) {
        return nodes.get(entityId);
    }

    /** Returns outgoing edges for the entity, or an empty list. */
    public List<GraphEdge> getNeighbors(long entityId) {
        List<GraphEdge> edges = adjacency.get(entityId);
        return (edges != null) ? Collections.unmodifiableList(edges) : Collections.emptyList();
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public long getEdgeCount() {
        return edgeCount.get();
    }

    /**
     * Graph density = E / (N * (N - 1)) for a directed graph.
     * Returns 0 if fewer than 2 nodes exist.
     */
    public double computeGraphDensity() {
        long n = nodes.size();
        if (n < 2) return 0.0;
        return (double) edgeCount.get() / (n * (n - 1));
    }

    /**
     * Returns all nodes whose risk magnitude exceeds the given threshold,
     * sorted descending by risk magnitude.
     */
    public List<EntityNode> findHighRiskNodes(double threshold) {
        List<EntityNode> result = new ArrayList<>();
        for (EntityNode node : nodes.values()) {
            if (node.computeRiskMagnitude() > threshold) {
                result.add(node);
            }
        }
        result.sort(Comparator.comparingDouble(EntityNode::computeRiskMagnitude).reversed());
        return result;
    }

    /** Provides read-only access to all nodes (used by math engine). */
    public Iterable<EntityNode> allNodes() {
        return nodes.values();
    }

    /** Checks whether an edge exists between source and target. */
    public boolean hasEdge(long sourceId, long targetId) {
        List<GraphEdge> edges = adjacency.get(sourceId);
        return edges != null && findEdge(edges, sourceId, targetId) != null;
    }

    /** Returns all source ids that have outgoing edges. */
    public Iterable<Long> allSourceIds() {
        return adjacency.keySet();
    }

    // ---- JSON summary ----

    /**
     * Produces a compact JSON summary: node count, edge count, density,
     * and the top-5 highest risk nodes.
     */
    public JSONObject toSummaryJson() {
        JSONObject json = new JSONObject();
        json.put("nodeCount", getNodeCount());
        json.put("edgeCount", getEdgeCount());
        json.put("density", computeGraphDensity());

        // Top-5 risk nodes
        List<EntityNode> risky = findHighRiskNodes(0.0); // all nodes, sorted
        JSONArray topRisk = new JSONArray();
        int limit = Math.min(5, risky.size());
        for (int i = 0; i < limit; i++) {
            EntityNode n = risky.get(i);
            JSONObject entry = new JSONObject();
            entry.put("entityId", n.getEntityId());
            entry.put("riskMagnitude", n.computeRiskMagnitude());
            topRisk.put(entry);
        }
        json.put("topRiskNodes", topRisk);
        return json;
    }

    /** Clears all nodes and edges (useful for testing). */
    public void clear() {
        nodes.clear();
        adjacency.clear();
        edgeCount.set(0);
    }

    // ---- Internal helpers ----

    /**
     * Derives a deterministic merchant id from merchant lat/lon.
     * Uses a simple hash to avoid collisions while keeping ids positive.
     */
    static long deriveMerchantId(TransactionData txn) {
        long latBits = Double.doubleToLongBits(txn.getMerchLat());
        long lonBits = Double.doubleToLongBits(txn.getMerchLon());
        // Combine with a prime-based hash and mask to ensure positive
        return (latBits * 31L + lonBits) & Long.MAX_VALUE;
    }

    /** Linear scan for an edge – adjacency lists per node are typically small. */
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
}
