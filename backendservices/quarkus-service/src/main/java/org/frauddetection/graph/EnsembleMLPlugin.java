package org.frauddetection.graph;

/**
 * Default ensemble ML plugin providing heuristic predictions for the
 * full 17-dimensional extended state vector.
 * <p>
 * This is a stub implementation — the prediction logic uses weighted
 * averages of the node state vector rather than a trained model.
 * Replace with a real model integration (ONNX, PMML, etc.) in production.
 * <p>
 * Weight layout covers all 17 dimensions:
 * <pre>
 *   Base (0–4):      [0.1, 0.2, 0.4, 0.2, 0.1]
 *   Temporal (5–8):  [0.15, 0.25, 0.1, 0.3]
 *   Behavioral (9–12): [0.2, 0.15, 0.35, 0.1]
 *   Network (13–16): [0.05, 0.2, 0.15, 0.3]
 * </pre>
 */
public class EnsembleMLPlugin implements MLIntegrationInterface {

    /** Weights for base risk prediction (backward-compatible). */
    private static final double[] BASE_RISK_WEIGHTS = {0.1, 0.2, 0.4, 0.2, 0.1};

    /** Weights for full extended state prediction. */
    private static final double[] EXTENDED_WEIGHTS = {
            0.10, 0.20, 0.40, 0.20, 0.10,   // base
            0.15, 0.25, 0.10, 0.30,           // temporal
            0.20, 0.15, 0.35, 0.10,           // behavioral
            0.05, 0.20, 0.15, 0.30            // network
    };

    @Override
    public double[] predictEntityRisk(EntityNode node) {
        double[] state = node.getStateVectorRef();
        double[] prediction = new double[EntityNode.STATE_DIM];

        // Weighted blend: each dimension contributes proportionally
        double weightedSum = 0.0;
        for (int i = 0; i < EntityNode.STATE_DIM; i++) {
            weightedSum += BASE_RISK_WEIGHTS[i] * state[i];
        }

        // Distribute the aggregate prediction back across dimensions
        for (int i = 0; i < EntityNode.STATE_DIM; i++) {
            prediction[i] = BASE_RISK_WEIGHTS[i] * weightedSum;
        }
        return prediction;
    }

    @Override
    public double[] predictExtendedState(EntityNode node) {
        double[] state = node.getExtendedStateVectorRef();
        double[] prediction = new double[ExtendedStateVector.EXTENDED_DIM];

        // Compute weighted sum across all available dimensions
        double weightedSum = 0.0;
        int dim = Math.min(state.length, EXTENDED_WEIGHTS.length);
        for (int i = 0; i < dim; i++) {
            weightedSum += EXTENDED_WEIGHTS[i] * state[i];
        }

        // Distribute back across all dimensions with per-dim weights
        for (int i = 0; i < ExtendedStateVector.EXTENDED_DIM; i++) {
            prediction[i] = EXTENDED_WEIGHTS[i] * weightedSum;
        }

        return prediction;
    }

    @Override
    public double predictEdgeStrength(GraphEdge edge) {
        // Return time-decayed weight as a simple strength prediction
        return edge.getDecayedWeight(System.currentTimeMillis());
    }

    @Override
    public void updateModel(TruthGraph graph) {
        // No-op: placeholder for future model retraining logic
    }
}
