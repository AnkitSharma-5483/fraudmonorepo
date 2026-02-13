package org.frauddetection.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java mathematical operations on the fraud detection graph.
 * <p>
 * All methods are stateless static utilities operating on primitive arrays
 * and graph structures.  No external math or graph library is used.
 */
public final class GraphMathEngine {

    private GraphMathEngine() {
        // utility class – not instantiable
    }

    /**
     * Projects a node's state vector onto the unit sphere (L2 normalization).
     * Returns a new array; the original is not modified.
     *
     * @return unit-length projection, or a zero vector if the input is zero
     */
    public static double[] projectState(EntityNode node) {
        double[] src = node.getStateVectorRef();
        double[] proj = new double[EntityNode.STATE_DIM];
        double norm = 0.0;
        for (int i = 0; i < EntityNode.STATE_DIM; i++) {
            norm += src[i] * src[i];
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return proj; // zero vector
        }
        for (int i = 0; i < EntityNode.STATE_DIM; i++) {
            proj[i] = src[i] / norm;
        }
        return proj;
    }

    /**
     * Interaction energy between two nodes, defined as the dot product of their
     * unit-sphere projections.  Range: [-1, 1].
     * <p>
     * High positive values indicate similar behaviour patterns.
     */
    public static double interactionEnergy(EntityNode a, EntityNode b) {
        double[] pa = projectState(a);
        double[] pb = projectState(b);
        double dot = 0.0;
        for (int i = 0; i < EntityNode.STATE_DIM; i++) {
            dot += pa[i] * pb[i];
        }
        return dot;
    }

    /**
     * Euclidean distance (L2 norm of the difference) between two vectors.
     */
    public static double deviationNorm(double[] predicted, double[] actual) {
        if (predicted.length != actual.length) {
            throw new IllegalArgumentException(
                    "Vector length mismatch: " + predicted.length + " vs " + actual.length);
        }
        double sumSq = 0.0;
        for (int i = 0; i < predicted.length; i++) {
            double d = predicted[i] - actual[i];
            sumSq += d * d;
        }
        return Math.sqrt(sumSq);
    }

    /**
     * Fraud hotspot score for an entity: weighted sum of neighbor risk magnitudes.
     * The weight is the (time-decayed) edge weight connecting the entity to each
     * neighbor.  An entity surrounded by high-risk nodes will score high.
     */
    public static double fraudHotspotScore(TruthGraph graph, long entityId) {
        List<GraphEdge> neighbors = graph.getNeighbors(entityId);
        if (neighbors.isEmpty()) {
            return 0.0;
        }

        long now = System.currentTimeMillis();
        double score = 0.0;
        double totalWeight = 0.0;

        for (GraphEdge edge : neighbors) {
            EntityNode neighbor = graph.getNode(edge.getTargetId());
            if (neighbor == null) continue;

            double w = edge.getDecayedWeight(now);
            score += w * neighbor.computeRiskMagnitude();
            totalWeight += w;
        }
        // Normalize to avoid unbounded growth
        return (totalWeight > 0.0) ? score / totalWeight : 0.0;
    }

    /**
     * Measures structural drift between an older snapshot and the current graph.
     * <p>
     * Drift = average L2 distance of state vectors for nodes present in both
     * graphs.  New or removed nodes contribute a fixed penalty.
     */
    public static double graphDrift(TruthGraph oldSnapshot, TruthGraph currentGraph) {
        double drift = 0.0;
        int count = 0;
        double missingPenalty = 1.0; // penalty per node that only exists in one graph

        // Compare nodes in old snapshot against current
        for (EntityNode oldNode : oldSnapshot.allNodes()) {
            count++;
            EntityNode curNode = currentGraph.getNode(oldNode.getEntityId());
            if (curNode == null) {
                drift += missingPenalty;
            } else {
                drift += deviationNorm(oldNode.getStateVectorRef(), curNode.getStateVectorRef());
            }
        }

        // Nodes only in current graph
        for (EntityNode curNode : currentGraph.allNodes()) {
            if (oldSnapshot.getNode(curNode.getEntityId()) == null) {
                drift += missingPenalty;
                count++;
            }
        }

        return (count > 0) ? drift / count : 0.0;
    }

    /**
     * Simplified power-iteration PageRank on the truth graph.
     * <p>
     * Complexity: O(iterations * (N + E)).  Uses primitive arrays internally
     * for efficiency; the result is returned as a map for convenience.
     *
     * @param graph      the truth graph to rank
     * @param iterations number of power iterations (typically 20–50)
     * @param damping    damping factor (typically 0.85)
     * @return map of entityId → PageRank score
     */
    public static Map<Long, Double> computePageRank(TruthGraph graph, int iterations, double damping) {
        int n = graph.getNodeCount();
        if (n == 0) {
            return Map.of();
        }

        // Assign dense indices to nodes for array-based computation
        Map<Long, Integer> idToIdx = new HashMap<>(n * 2);
        long[] idxToId = new long[n];
        int idx = 0;
        for (EntityNode node : graph.allNodes()) {
            idToIdx.put(node.getEntityId(), idx);
            idxToId[idx] = node.getEntityId();
            idx++;
        }

        // Precompute out-degree for each node
        int[] outDegree = new int[n];
        for (Long srcId : graph.allSourceIds()) {
            Integer srcIdx = idToIdx.get(srcId);
            if (srcIdx != null) {
                outDegree[srcIdx] = graph.getNeighbors(srcId).size();
            }
        }

        // Initialize ranks uniformly
        double[] rank = new double[n];
        double initRank = 1.0 / n;
        for (int i = 0; i < n; i++) {
            rank[i] = initRank;
        }

        double[] newRank = new double[n];
        double teleport = (1.0 - damping) / n;

        for (int iter = 0; iter < iterations; iter++) {
            // Reset new ranks to teleport component
            for (int i = 0; i < n; i++) {
                newRank[i] = teleport;
            }

            // Distribute rank along edges
            for (Long srcId : graph.allSourceIds()) {
                Integer srcIdx = idToIdx.get(srcId);
                if (srcIdx == null) continue;
                int deg = outDegree[srcIdx];
                if (deg == 0) continue;

                double share = damping * rank[srcIdx] / deg;
                for (GraphEdge edge : graph.getNeighbors(srcId)) {
                    Integer tgtIdx = idToIdx.get(edge.getTargetId());
                    if (tgtIdx != null) {
                        newRank[tgtIdx] += share;
                    }
                }
            }

            // Swap arrays (reuse old array as next newRank buffer)
            double[] tmp = rank;
            rank = newRank;
            newRank = tmp;
        }

        // Convert dense array back to map
        Map<Long, Double> result = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            result.put(idxToId[i], rank[i]);
        }
        return result;
    }
}
