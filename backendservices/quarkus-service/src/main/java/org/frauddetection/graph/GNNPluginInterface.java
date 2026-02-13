package org.frauddetection.graph;

import java.util.Map;

/**
 * Interface for future Graph Neural Network (GNN) integration.
 * <p>
 * Implementations should compute node embeddings by aggregating neighbourhood
 * features — for example via message-passing or graph-attention layers.
 * The embedding vectors can then feed into downstream fraud classifiers.
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
}
