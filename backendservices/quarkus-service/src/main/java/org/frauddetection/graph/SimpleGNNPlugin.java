package org.frauddetection.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default GNN implementation: 2-layer GraphSAGE with mean aggregation.
 * <p>
 * Architecture: For entity v at layer l:
 * <pre>
 *   h_v^(l) = σ(W^(l) · CONCAT(h_v^(l-1), MEAN(h_u^(l-1) for u ∈ N(v))))
 * </pre>
 * Where:
 * <ul>
 *   <li>h_v^(0) = extended state vector (R^17)</li>
 *   <li>W^(l) = weight matrix (initialized with Xavier-like scaling)</li>
 *   <li>σ = ReLU</li>
 *   <li>N(v) = sampled neighbors (capped at MAX_NEIGHBORS for scalability)</li>
 * </ul>
 * <p>
 * This is a heuristic implementation using fixed weight matrices (no training).
 * Replace with ONNX-backed inference for production GNN models.
 * <p>
 * <b>Semi-fused injection</b>: After computing embeddings, pairs with
 * cosine similarity > threshold get speculative edges tagged "DL" injected
 * into the hypothesis graph.
 */
public class SimpleGNNPlugin implements GNNPluginInterface {

    /** Output embedding dimension. */
    private static final int EMBEDDING_DIM = 64;

    /** Maximum neighbors to sample per hop (scalability bound). */
    private static final int MAX_NEIGHBORS = 25;

    /** Hidden dimension between layer 1 and layer 2. */
    private static final int HIDDEN_DIM = 32;

    /** Input dimension (extended state vector). */
    private static final int INPUT_DIM = ExtendedStateVector.EXTENDED_DIM;

    // Weight matrices (fixed initialization — Xavier-like scaling)
    // Layer 1: (INPUT_DIM * 2) → HIDDEN_DIM  (concat of self + neighbor aggregation)
    private final double[][] w1;
    // Layer 2: (HIDDEN_DIM * 2) → EMBEDDING_DIM
    private final double[][] w2;

    /** Cached embeddings from last batch computation. */
    private final Map<Long, double[]> cachedEmbeddings = new HashMap<>();

    public SimpleGNNPlugin() {
        this(42L);
    }

    public SimpleGNNPlugin(long seed) {
        w1 = initWeights(INPUT_DIM * 2, HIDDEN_DIM, seed);
        w2 = initWeights(HIDDEN_DIM * 2, EMBEDDING_DIM, seed + 1);
    }

    @Override
    public double[] computeNodeEmbedding(TruthGraph graph, long entityId) {
        EntityNode node = graph.getNode(entityId);
        if (node == null) return new double[EMBEDDING_DIM];

        // Layer 0: input features
        double[] selfFeatures = node.getExtendedStateVectorRef();

        // Layer 1: aggregate 1-hop neighbors
        double[] neighborAgg1 = aggregateNeighbors(graph, entityId, 0);
        double[] concat1 = concat(selfFeatures, neighborAgg1);
        double[] hidden = matMulRelu(concat1, w1);

        // Layer 2: aggregate 2-hop (using hidden representations of 1-hop neighbors)
        double[] neighborAgg2 = aggregateNeighborsHidden(graph, entityId);
        double[] concat2 = concat(hidden, neighborAgg2);
        double[] embedding = matMulRelu(concat2, w2);

        // L2 normalize the embedding
        double norm = 0.0;
        for (double v : embedding) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-10) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    @Override
    public Map<Long, double[]> computeAllEmbeddings(TruthGraph graph) {
        Map<Long, double[]> embeddings = new HashMap<>();
        for (EntityNode node : graph.allNodes()) {
            embeddings.put(node.getEntityId(),
                    computeNodeEmbedding(graph, node.getEntityId()));
        }
        cachedEmbeddings.clear();
        cachedEmbeddings.putAll(embeddings);
        return embeddings;
    }

    @Override
    public int injectSpeculativeEdges(TruthGraph graph, HypothesisGraph hypothesis, double threshold) {
        // Ensure embeddings are computed
        Map<Long, double[]> embeddings = cachedEmbeddings.isEmpty()
                ? computeAllEmbeddings(graph) : cachedEmbeddings;

        int injected = 0;
        List<Long> entityIds = new ArrayList<>(embeddings.keySet());

        // Compare all pairs (for small graphs; sampling for large ones)
        int maxPairs = Math.min(entityIds.size(), 500); // cap for scalability

        for (int i = 0; i < maxPairs; i++) {
            long idA = entityIds.get(i);
            double[] embA = embeddings.get(idA);

            for (int j = i + 1; j < maxPairs; j++) {
                long idB = entityIds.get(j);
                double[] embB = embeddings.get(idB);

                double similarity = GraphMathEngine.cosineSimilarity(embA, embB);

                if (similarity > threshold) {
                    // Check if edge already exists in hypothesis
                    boolean existsAB = false, existsBA = false;
                    for (GraphEdge e : hypothesis.getSpeculativeEdges()) {
                        if (e.getSourceId() == idA && e.getTargetId() == idB) existsAB = true;
                        if (e.getSourceId() == idB && e.getTargetId() == idA) existsBA = true;
                    }

                    double confidence = similarity - threshold;
                    if (!existsAB) {
                        hypothesis.addSpeculativeEdge(idA, idB, confidence, "DL");
                        injected++;
                    }
                    if (!existsBA) {
                        hypothesis.addSpeculativeEdge(idB, idA, confidence, "DL");
                        injected++;
                    }
                }
            }
        }

        return injected;
    }

    @Override
    public int getEmbeddingDimension() {
        return EMBEDDING_DIM;
    }

    /**
     * Returns cached embeddings from the last batch computation.
     */
    public Map<Long, double[]> getCachedEmbeddings() {
        return new HashMap<>(cachedEmbeddings);
    }

    // ---- Internal helpers ----

    /**
     * Mean aggregation of neighbor features (layer 0 features).
     */
    private double[] aggregateNeighbors(TruthGraph graph, long entityId, int depth) {
        List<GraphEdge> neighbors = graph.getNeighbors(entityId);
        double[] agg = new double[INPUT_DIM];
        if (neighbors.isEmpty()) return agg;

        int count = 0;
        for (GraphEdge e : neighbors) {
            if (count >= MAX_NEIGHBORS) break;
            EntityNode neighbor = graph.getNode(e.getTargetId());
            if (neighbor == null) continue;

            double[] nState = neighbor.getExtendedStateVectorRef();
            for (int i = 0; i < INPUT_DIM && i < nState.length; i++) {
                agg[i] += nState[i];
            }
            count++;
        }

        if (count > 0) {
            for (int i = 0; i < INPUT_DIM; i++) {
                agg[i] /= count;
            }
        }
        return agg;
    }

    /**
     * Layer-2 aggregation: average the hidden representations of neighbors.
     * Uses a simplified approach — runs layer-1 on each neighbor.
     */
    private double[] aggregateNeighborsHidden(TruthGraph graph, long entityId) {
        List<GraphEdge> neighbors = graph.getNeighbors(entityId);
        double[] agg = new double[HIDDEN_DIM];
        if (neighbors.isEmpty()) return agg;

        int count = 0;
        for (GraphEdge e : neighbors) {
            if (count >= MAX_NEIGHBORS) break;
            EntityNode neighbor = graph.getNode(e.getTargetId());
            if (neighbor == null) continue;

            // Run layer 1 on neighbor
            double[] nFeatures = neighbor.getExtendedStateVectorRef();
            double[] nNeighborAgg = aggregateNeighbors(graph, e.getTargetId(), 0);
            double[] nConcat = concat(nFeatures, nNeighborAgg);
            double[] nHidden = matMulRelu(nConcat, w1);

            for (int i = 0; i < HIDDEN_DIM && i < nHidden.length; i++) {
                agg[i] += nHidden[i];
            }
            count++;
        }

        if (count > 0) {
            for (int i = 0; i < HIDDEN_DIM; i++) {
                agg[i] /= count;
            }
        }
        return agg;
    }

    /** Concatenates two vectors. */
    private static double[] concat(double[] a, double[] b) {
        double[] result = new double[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /** Matrix-vector multiply followed by ReLU activation. */
    private static double[] matMulRelu(double[] input, double[][] weights) {
        int outDim = weights[0].length;
        int inDim = Math.min(input.length, weights.length);
        double[] output = new double[outDim];

        for (int j = 0; j < outDim; j++) {
            double sum = 0.0;
            for (int i = 0; i < inDim; i++) {
                sum += input[i] * weights[i][j];
            }
            output[j] = Math.max(0.0, sum); // ReLU
        }
        return output;
    }

    /** Initialize weights with Xavier-like scaling. */
    private static double[][] initWeights(int inDim, int outDim, long seed) {
        double[][] w = new double[inDim][outDim];
        double scale = Math.sqrt(2.0 / (inDim + outDim));
        long state = seed;
        for (int i = 0; i < inDim; i++) {
            for (int j = 0; j < outDim; j++) {
                // Simple LCG for deterministic pseudo-random init
                state = state * 6364136223846793005L + 1442695040888963407L;
                double uniform = ((state >>> 33) & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
                w[i][j] = (uniform * 2.0 - 1.0) * scale;
            }
        }
        return w;
    }
}
