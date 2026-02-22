package org.frauddetection.graph;

/**
 * Interface for pluggable ML model integrations.
 * <p>
 * Implementations can provide risk predictions for entities and edges,
 * and receive periodic retraining signals as the truth graph evolves.
 * <p>
 * The ML layer is responsible for predicting the temporal and behavioral
 * features (dimensions 5–12) of the extended state vector. Network features
 * (dimensions 13–16) are handled by {@link NetworkFeatureEngine}.
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

    /**
     * Predicts the full extended state vector (17-dim) for an entity,
     * including ML-derived temporal and behavioral features (dims 5–12).
     * <p>
     * Default implementation delegates to {@link #predictEntityRisk(EntityNode)}
     * and fills remaining dimensions with zeros. Override for full 17-dim prediction.
     *
     * @param node the entity node to evaluate
     * @return predicted extended state vector (17-dim)
     */
    default double[] predictExtendedState(EntityNode node) {
        double[] baseRisk = predictEntityRisk(node);
        double[] extended = new double[ExtendedStateVector.EXTENDED_DIM];
        System.arraycopy(baseRisk, 0, extended, 0,
                Math.min(baseRisk.length, ExtendedStateVector.EXTENDED_DIM));
        return extended;
    }
}
