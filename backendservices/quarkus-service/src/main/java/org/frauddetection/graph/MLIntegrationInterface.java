package org.frauddetection.graph;

/**
 * Interface for pluggable ML model integrations.
 * <p>
 * Implementations can provide risk predictions for entities and edges,
 * and receive periodic retraining signals as the truth graph evolves.
 */
public interface MLIntegrationInterface {

    /**
     * Predicts risk scores for the given entity.
     *
     * @param node the entity node to evaluate
     * @return an array of predicted risk dimensions (same layout as state vector)
     */
    double[] predictEntityRisk(EntityNode node);

    /**
     * Predicts the strength / likelihood of a given edge.
     *
     * @param edge the edge to evaluate
     * @return predicted strength in [0, ∞); higher means more likely
     */
    double predictEdgeStrength(GraphEdge edge);

    /**
     * Hook invoked when the truth graph has been updated, allowing the
     * model to retrain or refresh internal state.
     *
     * @param graph the current truth graph
     */
    void updateModel(TruthGraph graph);
}
