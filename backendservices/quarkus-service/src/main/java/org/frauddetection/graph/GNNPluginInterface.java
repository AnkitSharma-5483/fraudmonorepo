package org.frauddetection.graph;

import java.util.List;
import java.util.Map;

/**
 * Interface for Graph Neural Network (GNN) integration.
 * <p>
 * Implementations compute node embeddings by aggregating neighbourhood
 * features — for example via message-passing or graph-attention layers.
 * The embedding vectors feed into downstream fraud classifiers and are
 * exported to Partner B for Neo4j/Spark consumption.
 * <p>
 * <b>Semi-fused injection protocol</b>: After the math corrector step completes,
 * the GNN computes embeddings and identifies high-similarity pairs. For pairs
 * where cosine(e_u, e_v) > τ and no edge exists in the hypothesis graph,
 * a speculative edge tagged "DL" is injected. The math corrector tracks
 * DL speculation accuracy separately.
 */
public interface GNNPluginInterface {

    /**
     * Computes a low-dimensional embedding for a single node.
     *
     * @param graph    the truth graph providing structure and features
     * @param entityId the node to embed
     * @return embedding vector (dimensionality is implementation-defined)
     */
    double[] computeNodeEmbedding(TruthGraph graph, long entityId);

    /**
     * Batch-computes embeddings for every node in the graph.
     *
     * @param graph the truth graph
     * @return map of entityId → embedding vector
     */
    Map<Long, double[]> computeAllEmbeddings(TruthGraph graph);

    /**
     * Identifies high-similarity entity pairs based on embeddings and injects
     * speculative edges into the hypothesis graph tagged as "DL".
     * <p>
     * Only pairs where cosine similarity exceeds the threshold AND no edge
     * exists in the hypothesis graph are injected.
     *
     * @param graph      the truth graph providing features
     * @param hypothesis the hypothesis graph to inject edges into
     * @param threshold  minimum cosine similarity to trigger edge injection
     * @return number of edges injected
     */
    int injectSpeculativeEdges(TruthGraph graph, HypothesisGraph hypothesis, double threshold);

    /**
     * Returns the embedding dimensionality.
     */
    int getEmbeddingDimension();
}
