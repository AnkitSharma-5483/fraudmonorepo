package org.frauddetection.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes network-derived features (dimensions 13–16 of the extended state vector)
 * directly from the graph structure.
 * <p>
 * These features require graph topology access and are computed in Java
 * (unlike temporal/behavioral features which are computed by the Python ML layer).
 * <p>
 * Features:
 * <ul>
 *   <li>[13] communityId — label propagation community assignment</li>
 *   <li>[14] bridgeScore — approximate betweenness via random walks</li>
 *   <li>[15] localClusteringCoeff — triangle density around the entity</li>
 *   <li>[16] kHopRiskPropagation — heat-diffusion risk kernel</li>
 * </ul>
 * <p>
 * All computations are bounded by local neighborhood size, not full graph.
 */
public class NetworkFeatureEngine {

    /** Number of random walks for betweenness approximation. */
    private static final int BETWEENNESS_WALKS = 100;

    /** Walk length for betweenness approximation. */
    private static final int BETWEENNESS_WALK_LENGTH = 10;

    /** Number of label propagation iterations. */
    private static final int LABEL_PROP_ITERATIONS = 10;

    /** Heat diffusion hops. */
    private static final int HEAT_DIFFUSION_K = 3;

    /** Heat diffusion time parameter. */
    private static final double HEAT_DIFFUSION_T = 1.0;

    /** Cached community labels from last full computation. */
    private final ConcurrentHashMap<Long, Long> communityLabels = new ConcurrentHashMap<>();

    /**
     * Computes all four network features for a single entity and writes them
     * directly into the entity's extended state vector (indices 13–16).
     *
     * @param graph    the truth graph providing structure
     * @param entityId the entity to compute features for
     */
    public void computeForEntity(TruthGraph graph, long entityId) {
        EntityNode node = graph.getNode(entityId);
        if (node == null) return;

        // [14] Bridge score (approximate betweenness)
        double bridgeScore = GraphMathEngine.approximateBetweenness(
                graph, entityId, BETWEENNESS_WALKS, BETWEENNESS_WALK_LENGTH);
        node.setExtendedDimension(ExtendedStateVector.IDX_BRIDGE_SCORE, bridgeScore);

        // [15] Local clustering coefficient
        double clusterCoeff = GraphMathEngine.localClusteringCoefficient(graph, entityId);
        node.setExtendedDimension(ExtendedStateVector.IDX_LOCAL_CLUSTERING_COEFF, clusterCoeff);

        // [16] k-hop risk propagation via heat diffusion
        double heatRisk = GraphMathEngine.heatDiffusionRisk(
                graph, entityId, HEAT_DIFFUSION_K, HEAT_DIFFUSION_T);
        node.setExtendedDimension(ExtendedStateVector.IDX_K_HOP_RISK_PROPAGATION, heatRisk);

        // [13] Community ID (use cached value if available, else assign self)
        Long community = communityLabels.get(entityId);
        node.setExtendedDimension(ExtendedStateVector.IDX_COMMUNITY_ID,
                (community != null) ? community : entityId);
    }

    /**
     * Runs label propagation on the entire graph to compute community assignments.
     * Should be called periodically (e.g., every N transactions) rather than per-transaction.
     * <p>
     * Algorithm: Each node starts with its own ID as label. In each iteration,
     * every node adopts the most frequent label among its neighbors.
     * Converges in O(iterations × (N + E)) time.
     *
     * @param graph the truth graph
     */
    public void runLabelPropagation(TruthGraph graph) {
        // Initialize: each node gets its own ID as label
        Map<Long, Long> labels = new HashMap<>();
        for (EntityNode node : graph.allNodes()) {
            labels.put(node.getEntityId(), node.getEntityId());
        }

        // Iterate
        for (int iter = 0; iter < LABEL_PROP_ITERATIONS; iter++) {
            Map<Long, Long> newLabels = new HashMap<>(labels);
            boolean changed = false;

            for (EntityNode node : graph.allNodes()) {
                long entityId = node.getEntityId();
                List<GraphEdge> neighbors = graph.getNeighbors(entityId);
                if (neighbors.isEmpty()) continue;

                // Count label frequencies among neighbors (weighted by edge weight)
                Map<Long, Double> labelWeights = new HashMap<>();
                for (GraphEdge e : neighbors) {
                    Long neighborLabel = labels.get(e.getTargetId());
                    if (neighborLabel != null) {
                        labelWeights.merge(neighborLabel, e.getWeight(), Double::sum);
                    }
                }

                // Adopt the most frequent (highest weighted) label
                Long bestLabel = entityId;
                double bestWeight = 0.0;
                for (Map.Entry<Long, Double> entry : labelWeights.entrySet()) {
                    if (entry.getValue() > bestWeight) {
                        bestWeight = entry.getValue();
                        bestLabel = entry.getKey();
                    }
                }

                if (!bestLabel.equals(labels.get(entityId))) {
                    newLabels.put(entityId, bestLabel);
                    changed = true;
                }
            }

            labels = newLabels;
            if (!changed) break; // converged
        }

        // Cache results
        communityLabels.clear();
        communityLabels.putAll(labels);

        // Write community IDs into nodes
        for (Map.Entry<Long, Long> entry : labels.entrySet()) {
            EntityNode node = graph.getNode(entry.getKey());
            if (node != null) {
                node.setExtendedDimension(ExtendedStateVector.IDX_COMMUNITY_ID,
                        entry.getValue());
            }
        }
    }

    /**
     * Computes all network features for all nodes in the graph.
     * Expensive — should be called periodically, not per-transaction.
     *
     * @param graph the truth graph
     */
    public void computeAllNetworkFeatures(TruthGraph graph) {
        runLabelPropagation(graph);
        for (EntityNode node : graph.allNodes()) {
            computeForEntity(graph, node.getEntityId());
        }
    }

    /**
     * Returns the community label for an entity.
     */
    public Long getCommunityLabel(long entityId) {
        return communityLabels.get(entityId);
    }

    /**
     * Returns the number of distinct communities detected.
     */
    public int getCommunityCount() {
        return (int) communityLabels.values().stream().distinct().count();
    }

    /** Clears cached data (for testing). */
    public void clear() {
        communityLabels.clear();
    }
}
