package org.frauddetection.graph;

import org.frauddetection.model.TransactionData;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Primary evolving model of the fraud network.
 * <p>
 * In the inverted predictor-corrector paradigm, the HypothesisGraph is the
 * <b>primary state</b> — it carries the "best current belief" about the entire
 * network topology and per-entity state. The TruthGraph provides observations,
 * and only the <b>correction deltas</b> (what the hypothesis got wrong) are
 * stored in the {@link CorrectionDeltaGraph}.
 * <p>
 * Pipeline per transaction:
 * <ol>
 *   <li><b>Predictor</b>: Jacobian-based state extrapolation + speculative edges</li>
 *   <li><b>Observation</b>: real transaction arrives via TruthGraph</li>
 *   <li><b>Corrector</b>: compute innovation, apply Kalman gain, store compressed delta</li>
 *   <li><b>DL Enrichment</b>: GNN/GAN inject additional speculative edges (tagged source=DL)</li>
 * </ol>
 */
public class HypothesisGraph {

    // ---- Node and edge storage (full primary model) ----

    /** Hypothesized node states: entityId → cached predicted extended state vector. */
    private final ConcurrentHashMap<Long, double[]> predictedStates = new ConcurrentHashMap<>();

    /** Previous state vectors for Jacobian extrapolation: entityId → previous state. */
    private final ConcurrentHashMap<Long, double[]> previousStates = new ConcurrentHashMap<>();

    /** Speculative adjacency: sourceId → list of speculative edges. */
    private final ConcurrentHashMap<Long, List<GraphEdge>> speculativeAdj = new ConcurrentHashMap<>();

    /** Tracks speculation source for each edge: "MATH" or "DL". */
    private final ConcurrentHashMap<String, String> edgeSourceTags = new ConcurrentHashMap<>();

    // ---- Edge management ----

    /**
     * Adds a speculative edge with the given confidence as its weight.
     * If an edge between source and target already exists, its weight is updated
     * to the maximum of the old and new confidence.
     *
     * @param source     source entity ID
     * @param target     target entity ID
     * @param confidence confidence weight
     * @param sourceTag  "MATH" or "DL" — tracks which pipeline produced this edge
     */
    public void addSpeculativeEdge(long source, long target, double confidence, String sourceTag) {
        List<GraphEdge> edges = speculativeAdj.computeIfAbsent(source,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (edges) {
            for (GraphEdge e : edges) {
                if (e.getTargetId() == target) {
                    // Strengthen existing speculation
                    e.incrementFlow(confidence);
                    return;
                }
            }
            edges.add(new GraphEdge(source, target, confidence));
        }

        // Tag the edge source
        edgeSourceTags.put(source + "->" + target, sourceTag);
    }

    /**
     * Backward-compatible overload using "MATH" as default source tag.
     */
    public void addSpeculativeEdge(long source, long target, double confidence) {
        addSpeculativeEdge(source, target, confidence, "MATH");
    }

    /**
     * Computes a deviation metric: the fraction of speculative edges that do
     * NOT exist in the truth graph.  Returns a value in [0, 1]; lower is better.
     */
    public double compareWithTruth(TruthGraph truth) {
        long totalSpeculative = 0;
        long missing = 0;

        for (List<GraphEdge> edges : speculativeAdj.values()) {
            synchronized (edges) {
                for (GraphEdge e : edges) {
                    totalSpeculative++;
                    if (!truth.hasEdge(e.getSourceId(), e.getTargetId())) {
                        missing++;
                    }
                }
            }
        }
        return (totalSpeculative == 0) ? 0.0 : (double) missing / totalSpeculative;
    }

    /**
     * Computes deviation broken down by speculation source (MATH vs DL).
     *
     * @return array [mathDeviation, dlDeviation, overallDeviation]
     */
    public double[] compareWithTruthBySource(TruthGraph truth) {
        long mathTotal = 0, mathMissing = 0;
        long dlTotal = 0, dlMissing = 0;

        for (List<GraphEdge> edges : speculativeAdj.values()) {
            synchronized (edges) {
                for (GraphEdge e : edges) {
                    String tag = edgeSourceTags.getOrDefault(
                            e.getSourceId() + "->" + e.getTargetId(), "MATH");
                    boolean missing = !truth.hasEdge(e.getSourceId(), e.getTargetId());

                    if ("DL".equals(tag)) {
                        dlTotal++;
                        if (missing) dlMissing++;
                    } else {
                        mathTotal++;
                        if (missing) mathMissing++;
                    }
                }
            }
        }

        double mathDeviation = (mathTotal == 0) ? 0.0 : (double) mathMissing / mathTotal;
        double dlDeviation = (dlTotal == 0) ? 0.0 : (double) dlMissing / dlTotal;
        long allTotal = mathTotal + dlTotal;
        double overall = (allTotal == 0) ? 0.0 : (double) (mathMissing + dlMissing) / allTotal;

        return new double[]{mathDeviation, dlDeviation, overall};
    }

    /**
     * Removes speculative edges whose weight (confidence) is below the threshold.
     */
    public void pruneWeakEdges(double minConfidence) {
        for (List<GraphEdge> edges : speculativeAdj.values()) {
            synchronized (edges) {
                Iterator<GraphEdge> it = edges.iterator();
                while (it.hasNext()) {
                    GraphEdge e = it.next();
                    if (e.getWeight() < minConfidence) {
                        edgeSourceTags.remove(e.getSourceId() + "->" + e.getTargetId());
                        it.remove();
                    }
                }
            }
        }
        // Remove empty adjacency entries to keep the map lean
        speculativeAdj.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * Returns a flat snapshot of all speculative edges.
     */
    public List<GraphEdge> getSpeculativeEdges() {
        List<GraphEdge> all = new ArrayList<>();
        for (List<GraphEdge> edges : speculativeAdj.values()) {
            synchronized (edges) {
                all.addAll(edges);
            }
        }
        return all;
    }

    /**
     * Returns the source tag for an edge ("MATH" or "DL").
     */
    public String getEdgeSourceTag(long sourceId, long targetId) {
        return edgeSourceTags.getOrDefault(sourceId + "->" + targetId, "MATH");
    }

    // ---- State prediction / caching ----

    /**
     * Stores a predicted extended state vector for an entity.
     * Called during the predictor step.
     */
    public void setPredictedState(long entityId, double[] predictedState) {
        // Save current as previous before overwriting
        double[] current = predictedStates.get(entityId);
        if (current != null) {
            previousStates.put(entityId, current);
        }
        predictedStates.put(entityId, predictedState.clone());
    }

    /**
     * Returns the predicted state for an entity, or null if no prediction exists.
     */
    public double[] getPredictedState(long entityId) {
        double[] state = predictedStates.get(entityId);
        return (state != null) ? state.clone() : null;
    }

    /**
     * Returns the previous state for an entity (for Jacobian extrapolation).
     */
    public double[] getPreviousState(long entityId) {
        double[] state = previousStates.get(entityId);
        return (state != null) ? state.clone() : null;
    }

    /**
     * Returns the number of entities with predicted states.
     */
    public int getPredictedNodeCount() {
        return predictedStates.size();
    }

    // ---- Predictor / Corrector cycle ----

    /**
     * <b>Predictor step (enhanced)</b>: Given a new transaction, perform:
     * <ol>
     *   <li>Jacobian-based state extrapolation (if previous state exists)</li>
     *   <li>Speculative edge generation (same-merchant + perturbed merchant)</li>
     * </ol>
     * <p>
     * The DL enrichment (GNN edge injection) happens AFTER the corrector step,
     * externally via {@link #addSpeculativeEdge(long, long, double, String)}.
     */
    public void predictorStep(TransactionData txn) {
        long sourceId = txn.getCcNum();
        long merchantId = TruthGraph.deriveMerchantId(txn);

        // ---- Jacobian-based state extrapolation ----
        double[] current = predictedStates.get(sourceId);
        double[] previous = previousStates.get(sourceId);

        if (current != null && previous != null) {
            // Estimate dt from transaction timestamps
            double dt = 1.0; // normalized; real dt comes from transaction intervals
            double dtPredicted = 1.0; // project one step forward
            double[] extrapolated = GraphMathEngine.jacobianExtrapolation(
                    current, previous, dt, dtPredicted);
            setPredictedState(sourceId, extrapolated);
        }
        // If no history, prediction will be set when corrector runs for the first time

        // ---- Speculative edges (pure math) ----

        // Speculate: source will transact again with this merchant
        double confidence = Math.min(txn.getAmt() / 1000.0, 1.0); // normalized confidence
        addSpeculativeEdge(sourceId, merchantId, confidence, "MATH");

        // Speculate: perturbed merchant id as a proxy for a related merchant
        long perturbedMerchantId = merchantId ^ 0xFFFL; // flip low bits for a variant
        addSpeculativeEdge(sourceId, perturbedMerchantId, confidence * 0.3, "MATH");
    }

    /**
     * <b>Corrector step (Kalman-enhanced)</b>: Compare speculative edges against
     * the truth graph, compute innovations, apply adaptive Kalman gain, and
     * produce correction deltas.
     *
     * @param truth   the truth graph (observation source)
     * @param kalman  the Kalman gain estimator
     * @param deltas  the correction delta store (receives compressed deltas)
     * @param txnSequence current transaction sequence number
     */
    public void correctorStep(TruthGraph truth, KalmanGainEstimator kalman,
                              CorrectionDeltaGraph deltas, long txnSequence) {
        // ---- Edge correction with source-aware deviation ----
        double[] deviations = compareWithTruthBySource(truth);
        double mathDeviation = deviations[0];
        double overallDeviation = deviations[2];

        // Adaptive pruning: higher deviation → more aggressive pruning
        double pruneThreshold = 0.1 + overallDeviation * 0.5;
        pruneWeakEdges(pruneThreshold);

        // ---- State correction via Kalman gain ----
        for (EntityNode observedNode : truth.allNodes()) {
            long entityId = observedNode.getEntityId();
            double[] observed = observedNode.getExtendedStateVectorRef();
            double[] predicted = predictedStates.get(entityId);

            if (predicted == null) {
                // First time seeing this entity — initialize prediction from observation
                setPredictedState(entityId, observed);
                continue;
            }

            // Compute innovation (prediction error)
            double[] innovation = GraphMathEngine.computeInnovation(observed, predicted);
            double innovationNorm = ExtendedStateVector.l2Norm(innovation);

            // Compute adaptive Kalman gain for this entity
            double gain = kalman.computeGain(entityId, innovationNorm);

            // Apply correction
            double[] corrected = GraphMathEngine.applyKalmanCorrection(predicted, innovation, gain);
            setPredictedState(entityId, corrected);

            // Record compressed delta
            if (innovationNorm > 1e-6) {
                // Determine the primary speculation source for this entity
                String source = "MATH"; // default
                List<GraphEdge> speculativeEdges = speculativeAdj.get(entityId);
                if (speculativeEdges != null) {
                    synchronized (speculativeEdges) {
                        for (GraphEdge e : speculativeEdges) {
                            if ("DL".equals(edgeSourceTags.getOrDefault(
                                    e.getSourceId() + "->" + e.getTargetId(), "MATH"))) {
                                source = "DL";
                                break;
                            }
                        }
                    }
                }

                CorrectionDelta delta = new CorrectionDelta(
                        entityId, txnSequence, innovation, 1e-4, source, gain);
                deltas.recordDelta(delta);

                // Record edge corrections for edges involving this entity
                if (speculativeEdges != null) {
                    synchronized (speculativeEdges) {
                        for (GraphEdge e : speculativeEdges) {
                            boolean inTruth = truth.hasEdge(e.getSourceId(), e.getTargetId());
                            if (!inTruth) {
                                deltas.recordEdgeCorrection(
                                        e.getSourceId(), e.getTargetId(),
                                        true, false, e.getWeight());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Legacy corrector step (backward-compatible, without Kalman).
     */
    public void correctorStep(TruthGraph truth) {
        double deviation = compareWithTruth(truth);
        double pruneThreshold = 0.1 + deviation * 0.5;
        pruneWeakEdges(pruneThreshold);
    }

    // ---- JSON summary ----

    /**
     * Returns a JSON summary with edge count, deviation placeholder, edges,
     * and predicted node count.
     */
    public JSONObject toSummaryJson() {
        List<GraphEdge> edges = getSpeculativeEdges();

        // Count by source
        long mathEdges = 0, dlEdges = 0;
        for (GraphEdge e : edges) {
            if ("DL".equals(getEdgeSourceTag(e.getSourceId(), e.getTargetId()))) {
                dlEdges++;
            } else {
                mathEdges++;
            }
        }

        JSONObject json = new JSONObject();
        json.put("speculativeEdgeCount", edges.size());
        json.put("mathEdges", mathEdges);
        json.put("dlEdges", dlEdges);
        json.put("predictedNodeCount", predictedStates.size());

        JSONArray edgeArr = new JSONArray();
        int limit = Math.min(10, edges.size()); // cap for readability
        for (int i = 0; i < limit; i++) {
            GraphEdge e = edges.get(i);
            JSONObject ej = new JSONObject();
            ej.put("source", e.getSourceId());
            ej.put("target", e.getTargetId());
            ej.put("confidence", e.getWeight());
            ej.put("sourceTag", getEdgeSourceTag(e.getSourceId(), e.getTargetId()));
            edgeArr.put(ej);
        }
        json.put("topEdges", edgeArr);
        return json;
    }

    /** Clears all state (for testing). */
    public void clear() {
        speculativeAdj.clear();
        edgeSourceTags.clear();
        predictedStates.clear();
        previousStates.clear();
    }
}
