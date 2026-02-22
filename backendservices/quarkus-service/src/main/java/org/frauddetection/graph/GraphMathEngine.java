package org.frauddetection.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java mathematical operations on the fraud detection graph.
 * <p>
 * All methods are stateless static utilities operating on primitive arrays
 * and graph structures.  No external math or graph library is used.
 * <p>
 * Includes:
 * <ul>
 *   <li>State projection (L2 normalization to unit sphere)</li>
 *   <li>Interaction energy (cosine similarity)</li>
 *   <li>Deviation norm (Euclidean distance)</li>
 *   <li>Fraud hotspot scoring (weighted neighbor risk)</li>
 *   <li>Graph drift measurement</li>
 *   <li>Power-iteration PageRank</li>
 *   <li>Heat-diffusion risk propagation (k-hop kernel)</li>
 *   <li>Innovation delta computation (for predictor-corrector)</li>
 *   <li>Jacobian-based state extrapolation</li>
 * </ul>
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
        if (norm < 1e-10) {
            return proj; // effectively zero vector
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

    // ========================================================================
    // Extended math operations for the inverted predictor-corrector paradigm
    // ========================================================================

    /**
     * Computes the innovation (correction delta) between the observed state and
     * the hypothesis-predicted state for an entity.
     * <p>
     * innovation[i] = observed[i] - predicted[i]
     * <p>
     * This is the core of the predictor-corrector: the innovation measures
     * what the hypothesis got wrong.
     *
     * @param observed  observed state vector (from the real transaction)
     * @param predicted predicted state vector (from the hypothesis graph)
     * @return element-wise difference vector
     */
    public static double[] computeInnovation(double[] observed, double[] predicted) {
        int dim = Math.min(observed.length, predicted.length);
        double[] innovation = new double[dim];
        for (int i = 0; i < dim; i++) {
            innovation[i] = observed[i] - predicted[i];
        }
        return innovation;
    }

    /**
     * Applies the Kalman gain to an innovation, producing the corrected state update.
     * <p>
     * corrected[i] = predicted[i] + gain * innovation[i]
     *
     * @param predicted  hypothesis-predicted state
     * @param innovation computed innovation vector
     * @param gain       scalar Kalman gain in [0, 1]
     * @return corrected state vector
     */
    public static double[] applyKalmanCorrection(double[] predicted, double[] innovation, double gain) {
        int dim = Math.min(predicted.length, innovation.length);
        double[] corrected = new double[Math.max(dim, ExtendedStateVector.EXTENDED_DIM)];
        System.arraycopy(predicted, 0, corrected, 0, predicted.length);
        for (int i = 0; i < dim; i++) {
            corrected[i] = predicted[i] + gain * innovation[i];
        }
        return corrected;
    }

    /**
     * Jacobian-based state extrapolation: estimates the next state by computing
     * finite-difference partial derivatives of each dimension with respect to
     * transaction count and projecting forward.
     * <p>
     * For each dimension i:
     *   dState[i]/dt ≈ (current[i] - previous[i]) / Δt
     *   predicted[i] = current[i] + dState[i]/dt * Δt_predicted
     * <p>
     * This is a first-order Taylor expansion of the state evolution.
     *
     * @param currentState  current state vector
     * @param previousState previous state vector (one step ago)
     * @param dt            time elapsed between previous and current
     * @param dtPredicted   time to extrapolate forward
     * @return predicted next state vector
     */
    public static double[] jacobianExtrapolation(double[] currentState, double[] previousState,
                                                  double dt, double dtPredicted) {
        int dim = Math.min(currentState.length, previousState.length);
        double[] predicted = new double[currentState.length];
        System.arraycopy(currentState, 0, predicted, 0, currentState.length);

        if (dt > 0) {
            for (int i = 0; i < dim; i++) {
                double derivative = (currentState[i] - previousState[i]) / dt;
                predicted[i] = currentState[i] + derivative * dtPredicted;
            }
        }
        return predicted;
    }

    /**
     * Heat-diffusion risk propagation using a truncated matrix exponential.
     * <p>
     * Computes: r_diffused = Σ_{i=0}^{k} (e^{-t} * t^i / i!) * A^i * r_0
     * <p>
     * where A is the normalized adjacency (edge weights / max weight), r_0 is the
     * initial risk vector, and t is the diffusion time parameter.
     * <p>
     * Implementation uses BFS to depth k from the given entity, avoiding full
     * matrix exponentiation. Complexity: O(Σ deg^i for i=1..k) per entity.
     *
     * @param graph    the truth graph providing structure
     * @param entityId the entity from which to diffuse
     * @param k        number of hops (typically 3)
     * @param t        diffusion time parameter (typically 1.0)
     * @return propagated risk score for the entity
     */
    public static double heatDiffusionRisk(TruthGraph graph, long entityId, int k, double t) {
        // Compute heat kernel coefficients: h_i = e^{-t} * t^i / i!
        double[] coefficients = new double[k + 1];
        double expNegT = Math.exp(-t);
        double tPow = 1.0;
        double factorial = 1.0;
        for (int i = 0; i <= k; i++) {
            coefficients[i] = expNegT * tPow / factorial;
            tPow *= t;
            factorial *= (i + 1);
        }

        // BFS-based diffusion from entityId
        // Level 0: the entity itself
        Map<Long, Double> currentLevel = new HashMap<>();
        EntityNode root = graph.getNode(entityId);
        if (root == null) return 0.0;

        currentLevel.put(entityId, root.computeRiskMagnitude());

        double propagatedRisk = coefficients[0] * root.computeRiskMagnitude();

        // Levels 1..k
        for (int hop = 1; hop <= k; hop++) {
            Map<Long, Double> nextLevel = new HashMap<>();

            for (Map.Entry<Long, Double> entry : currentLevel.entrySet()) {
                List<GraphEdge> neighbors = graph.getNeighbors(entry.getKey());
                if (neighbors.isEmpty()) continue;

                // Compute max weight for normalization
                double maxWeight = 0.0;
                for (GraphEdge e : neighbors) {
                    maxWeight = Math.max(maxWeight, e.getWeight());
                }
                if (maxWeight < 1e-10) continue;

                for (GraphEdge e : neighbors) {
                    EntityNode neighbor = graph.getNode(e.getTargetId());
                    if (neighbor == null) continue;

                    double normalizedWeight = e.getWeight() / maxWeight;
                    double riskContribution = normalizedWeight * neighbor.computeRiskMagnitude();

                    nextLevel.merge(e.getTargetId(), riskContribution, Double::sum);
                }
            }

            // Accumulate hop contribution
            for (double risk : nextLevel.values()) {
                propagatedRisk += coefficients[hop] * risk;
            }

            currentLevel = nextLevel;
        }

        return propagatedRisk;
    }

    /**
     * Extended projection: L2 normalization on the full extended state vector.
     *
     * @param extendedState a 17-dim state vector
     * @return unit-length projection
     */
    public static double[] projectExtendedState(double[] extendedState) {
        double[] proj = new double[extendedState.length];
        double norm = 0.0;
        for (double v : extendedState) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-10) return proj;
        for (int i = 0; i < extendedState.length; i++) {
            proj[i] = extendedState[i] / norm;
        }
        return proj;
    }

    /**
     * Extended fraud hotspot score using the full 17-dim state and heat diffusion.
     * Combines the original weighted-neighbor approach with heat-diffusion propagation.
     *
     * @param graph    the truth graph
     * @param entityId entity to score
     * @return combined hotspot score
     */
    public static double extendedFraudHotspotScore(TruthGraph graph, long entityId) {
        double baseHotspot = fraudHotspotScore(graph, entityId);
        double heatRisk = heatDiffusionRisk(graph, entityId, 3, 1.0);
        // Weighted combination: base contributes 60%, heat diffusion 40%
        return 0.6 * baseHotspot + 0.4 * heatRisk;
    }

    /**
     * Computes the cosine similarity between two extended state vectors.
     * Range: [-1, 1]. Used by GNN for edge injection decisions.
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        int dim = Math.min(a.length, b.length);
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < dim; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return (denom < 1e-10) ? 0.0 : dot / denom;
    }

    /**
     * Approximates betweenness centrality using random walks.
     * For each of {@code numWalks} random walks of length {@code walkLength},
     * records how often the target entity appears on the walk path.
     * <p>
     * Complexity: O(numWalks * walkLength) — much cheaper than exact betweenness O(VE).
     *
     * @param graph     the truth graph
     * @param entityId  entity to measure
     * @param numWalks  number of random walks to perform
     * @param walkLength maximum length of each walk
     * @return approximate betweenness score (fraction of walks passing through entity)
     */
    public static double approximateBetweenness(TruthGraph graph, long entityId,
                                                 int numWalks, int walkLength) {
        if (graph.getNodeCount() < 3) return 0.0;

        // Collect all node IDs for random starting points
        long[] allIds = new long[graph.getNodeCount()];
        int idx = 0;
        for (EntityNode n : graph.allNodes()) {
            allIds[idx++] = n.getEntityId();
        }

        int passCount = 0;
        long seed = entityId * 31L + System.nanoTime();

        for (int w = 0; w < numWalks; w++) {
            // Deterministic pseudo-random start (avoid java.util.Random allocation)
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int startIdx = (int) ((seed >>> 33) % allIds.length);
            if (startIdx < 0) startIdx = -startIdx;
            long current = allIds[startIdx];

            boolean passedThrough = false;
            for (int step = 0; step < walkLength; step++) {
                if (current == entityId && step > 0) {
                    passedThrough = true;
                    break;
                }
                List<GraphEdge> neighbors = graph.getNeighbors(current);
                if (neighbors.isEmpty()) break;

                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int nextIdx = (int) ((seed >>> 33) % neighbors.size());
                if (nextIdx < 0) nextIdx = -nextIdx;
                current = neighbors.get(nextIdx).getTargetId();
            }

            if (passedThrough) passCount++;
        }

        return (double) passCount / numWalks;
    }

    /**
     * Computes the local clustering coefficient for an entity:
     * triangles(v) / C(deg(v), 2)
     * <p>
     * Measures how tightly connected an entity's neighborhood is.
     * Range: [0, 1]. Higher values indicate the entity is part of a clique.
     */
    public static double localClusteringCoefficient(TruthGraph graph, long entityId) {
        List<GraphEdge> neighbors = graph.getNeighbors(entityId);
        int degree = neighbors.size();
        if (degree < 2) return 0.0;

        // Collect neighbor IDs into a set for O(1) lookup
        java.util.Set<Long> neighborSet = new java.util.HashSet<>(degree * 2);
        for (GraphEdge e : neighbors) {
            neighborSet.add(e.getTargetId());
        }

        // Count edges between neighbors (triangles)
        int triangles = 0;
        for (GraphEdge e : neighbors) {
            List<GraphEdge> neighborNeighbors = graph.getNeighbors(e.getTargetId());
            for (GraphEdge nn : neighborNeighbors) {
                if (neighborSet.contains(nn.getTargetId())) {
                    triangles++;
                }
            }
        }

        // Each triangle is counted twice (once from each end)
        double possibleTriangles = (double) degree * (degree - 1);
        return (possibleTriangles > 0) ? triangles / possibleTriangles : 0.0;
    }
}
