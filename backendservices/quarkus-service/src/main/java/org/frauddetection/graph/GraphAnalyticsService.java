package org.frauddetection.graph;

import jakarta.enterprise.context.ApplicationScoped;
import org.frauddetection.model.TransactionData;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Quarkus CDI service that orchestrates the full 6-stage graph analytical pipeline.
 * <p>
 * <b>Inverted Predictor-Corrector Paradigm</b>: The HypothesisGraph is the primary
 * evolving model. TruthGraph provides observations. CorrectionDeltaGraph stores only
 * the compressed "surprises" (what the hypothesis got wrong).
 * <p>
 * Pipeline stages per transaction:
 * <ol>
 *   <li><b>PREDICTION</b> — Jacobian extrapolation + speculative edges (pure math)</li>
 *   <li><b>OBSERVATION</b> — real transaction updates TruthGraph</li>
 *   <li><b>CORRECTION</b> — innovation via Kalman gain, compressed deltas stored</li>
 *   <li><b>ML ENRICHMENT</b> — extend state vector R^5 → R^17</li>
 *   <li><b>DL ENRICHMENT</b> — GNN embeddings + GAN anomaly scoring (semi-fused)</li>
 *   <li><b>EXPORT</b> — metrics, embeddings, anomalies available to Partner B</li>
 * </ol>
 */
@ApplicationScoped
public class GraphAnalyticsService {

    // ---- Core graph structures ----

    /** The confirmed-transaction graph (observation source). */
    private final TruthGraph truthGraph = new TruthGraph();

    /** The primary evolving model (hypothesis = best current belief). */
    private final HypothesisGraph hypothesisGraph = new HypothesisGraph();

    /** Compressed correction deltas (what the hypothesis got wrong). */
    private final CorrectionDeltaGraph correctionDeltas = new CorrectionDeltaGraph();

    /** Per-entity adaptive Kalman gain estimator. */
    private final KalmanGainEstimator kalmanEstimator = new KalmanGainEstimator();

    // ---- Feature engines ----

    /** Network feature engine (community, betweenness, clustering, heat diffusion). */
    private final NetworkFeatureEngine networkEngine = new NetworkFeatureEngine();

    /** ML plugin for risk prediction and extended state computation. */
    private final MLIntegrationInterface mlPlugin = new EnsembleMLPlugin();

    // ---- DL plugins (semi-fused) ----

    /** GNN plugin for embeddings and speculative edge injection. */
    private final SimpleGNNPlugin gnnPlugin = new SimpleGNNPlugin();

    /** GAN plugin for anomaly detection and scenario generation. */
    private final SimpleGANPlugin ganPlugin = new SimpleGANPlugin();

    // ---- Counters and config ----

    /** Total transactions processed through the graph pipeline. */
    private final AtomicLong transactionCounter = new AtomicLong(0);

    /** GNN similarity threshold for edge injection. */
    private static final double GNN_INJECTION_THRESHOLD = 0.8;

    /** GAN anomaly score threshold for fraud candidates. */
    private static final double GAN_ANOMALY_THRESHOLD = 0.6;

    /** Run full network features every N transactions. */
    private static final int NETWORK_FEATURE_INTERVAL = 50;

    /** Run DL enrichment every N transactions. */
    private static final int DL_ENRICHMENT_INTERVAL = 10;

    // ---- Main 6-stage pipeline ----

    /**
     * Processes a single transaction through the full 6-stage pipeline.
     *
     * @return JSON object with graph-derived metrics for this transaction
     */
    public JSONObject processTransaction(TransactionData txn) {
        long txnNum = transactionCounter.incrementAndGet();

        // ===== STAGE 1: PREDICTION (Pure Math) =====
        hypothesisGraph.predictorStep(txn);

        // ===== STAGE 2: OBSERVATION =====
        truthGraph.addTransaction(txn);

        // ===== STAGE 3: CORRECTION (Kalman-enhanced) =====
        hypothesisGraph.correctorStep(truthGraph, kalmanEstimator, correctionDeltas, txnNum);

        // ===== STAGE 4: ML ENRICHMENT (R^5 → R^17) =====
        long sourceId = txn.getCcNum();
        EntityNode sourceNode = truthGraph.getNode(sourceId);

        // Compute per-entity network features (lightweight: bridge, clustering, heat)
        networkEngine.computeForEntity(truthGraph, sourceId);

        // Periodically recompute full network features (community detection)
        if (txnNum % NETWORK_FEATURE_INTERVAL == 0) {
            networkEngine.computeAllNetworkFeatures(truthGraph);
        }

        // ===== STAGE 5: DL ENRICHMENT (Semi-Fused) =====
        int dlEdgesInjected = 0;
        if (txnNum % DL_ENRICHMENT_INTERVAL == 0 && truthGraph.getNodeCount() >= 3) {
            // GNN: compute embeddings and inject speculative edges
            dlEdgesInjected = gnnPlugin.injectSpeculativeEdges(
                    truthGraph, hypothesisGraph, GNN_INJECTION_THRESHOLD);
        }

        // ===== STAGE 6: COMPUTE METRICS & EXPORT =====

        // Fraud hotspot score (extended: base + heat diffusion)
        double hotspot = GraphMathEngine.extendedFraudHotspotScore(truthGraph, sourceId);

        // Hypothesis deviation by source (MATH vs DL)
        double[] deviations = hypothesisGraph.compareWithTruthBySource(truthGraph);

        // Assemble response
        JSONObject result = new JSONObject();
        result.put("transactionNumber", txnNum);
        result.put("entityId", sourceId);
        result.put("fraudHotspotScore", hotspot);
        result.put("riskMagnitude", sourceNode != null ? sourceNode.computeRiskMagnitude() : 0.0);
        result.put("extendedRiskMagnitude", sourceNode != null ? sourceNode.computeExtendedRiskMagnitude() : 0.0);
        result.put("graphNodeCount", truthGraph.getNodeCount());
        result.put("graphEdgeCount", truthGraph.getEdgeCount());
        result.put("hypothesisDeviation", deviations[2]); // overall
        result.put("mathSpeculationDeviation", deviations[0]);
        result.put("dlSpeculationDeviation", deviations[1]);
        result.put("kalmanGain", kalmanEstimator.getCurrentGain(sourceId));
        result.put("avgDeltaNorm", correctionDeltas.getRunningAvgDeltaNorm());
        result.put("dlEdgesInjected", dlEdgesInjected);
        result.put("predictedNodeCount", hypothesisGraph.getPredictedNodeCount());

        // Extended state vector breakdown (if node exists)
        if (sourceNode != null) {
            double[] ext = sourceNode.getExtendedStateVector();
            JSONObject extState = new JSONObject();
            for (int i = 0; i < ExtendedStateVector.EXTENDED_DIM && i < ext.length; i++) {
                extState.put(ExtendedStateVector.DIMENSION_NAMES[i], ext[i]);
            }
            result.put("extendedState", extState);
        }

        return result;
    }

    // ---- Query methods ----

    /**
     * Returns a full summary of truth, hypothesis, and correction delta graphs.
     */
    public JSONObject getGraphState() {
        JSONObject state = new JSONObject();
        state.put("transactionsProcessed", transactionCounter.get());
        state.put("truthGraph", truthGraph.toSummaryJson());
        state.put("hypothesisGraph", hypothesisGraph.toSummaryJson());
        state.put("correctionDeltas", correctionDeltas.toSummaryJson());

        // Convergence metrics for Partner B
        JSONObject convergence = new JSONObject();
        convergence.put("mathSpeculationAccuracy", correctionDeltas.getMathSpeculationAccuracy());
        convergence.put("dlSpeculationAccuracy", correctionDeltas.getDlSpeculationAccuracy());
        convergence.put("avgDeltaNorm", correctionDeltas.getRunningAvgDeltaNorm());
        convergence.put("kalmanTrackedEntities", kalmanEstimator.getTrackedEntityCount());
        convergence.put("communityCount", networkEngine.getCommunityCount());
        state.put("convergenceMetrics", convergence);

        return state;
    }

    /**
     * Returns detailed state for a single entity, including extended features,
     * predicted state, and recent correction deltas.
     */
    public JSONObject getEntityState(long entityId) {
        EntityNode node = truthGraph.getNode(entityId);
        JSONObject json = new JSONObject();
        json.put("entityId", entityId);

        if (node == null) {
            json.put("found", false);
            return json;
        }

        json.put("found", true);
        json.put("riskMagnitude", node.computeRiskMagnitude());
        json.put("extendedRiskMagnitude", node.computeExtendedRiskMagnitude());
        json.put("lastUpdateTime", node.getLastUpdateTime());

        // Full extended state vector breakdown
        double[] ext = node.getExtendedStateVector();
        JSONObject stateJson = new JSONObject();
        for (int i = 0; i < ExtendedStateVector.EXTENDED_DIM && i < ext.length; i++) {
            stateJson.put(ExtendedStateVector.DIMENSION_NAMES[i], ext[i]);
        }
        json.put("stateVector", stateJson);

        // Projected state (unit sphere, extended)
        double[] proj = GraphMathEngine.projectExtendedState(ext);
        JSONArray projArr = new JSONArray();
        for (double v : proj) {
            projArr.put(v);
        }
        json.put("projectedState", projArr);

        // Neighbors and hotspot
        List<GraphEdge> neighbors = truthGraph.getNeighbors(entityId);
        json.put("neighborCount", neighbors.size());
        json.put("fraudHotspotScore", GraphMathEngine.extendedFraudHotspotScore(truthGraph, entityId));

        // Kalman gain state
        json.put("kalmanGain", kalmanEstimator.getCurrentGain(entityId));
        json.put("processVariance", kalmanEstimator.getProcessVariance(entityId));

        // Hypothesis prediction for this entity
        double[] predicted = hypothesisGraph.getPredictedState(entityId);
        if (predicted != null) {
            JSONObject predJson = new JSONObject();
            for (int i = 0; i < ExtendedStateVector.EXTENDED_DIM && i < predicted.length; i++) {
                predJson.put(ExtendedStateVector.DIMENSION_NAMES[i], predicted[i]);
            }
            json.put("predictedState", predJson);
        }

        // Recent correction deltas
        List<CorrectionDelta> recentDeltas = correctionDeltas.getRecentDeltas(entityId, 5);
        if (!recentDeltas.isEmpty()) {
            JSONArray deltaArr = new JSONArray();
            for (CorrectionDelta d : recentDeltas) {
                JSONObject dj = new JSONObject();
                dj.put("txnSequence", d.getTxnSequence());
                dj.put("deltaNorm", d.getDeltaNorm());
                dj.put("changedDims", d.getChangedDimensionCount());
                dj.put("source", d.getSpeculationSource());
                dj.put("gain", d.getAppliedGain());
                deltaArr.put(dj);
            }
            json.put("recentDeltas", deltaArr);
        }

        // Community info
        Long community = networkEngine.getCommunityLabel(entityId);
        if (community != null) {
            json.put("communityId", community);
        }

        return json;
    }

    /**
     * Returns graph-wide analytical metrics including PageRank, convergence,
     * and DL statistics.
     */
    public JSONObject getGraphMetrics() {
        JSONObject metrics = new JSONObject();
        metrics.put("nodeCount", truthGraph.getNodeCount());
        metrics.put("edgeCount", truthGraph.getEdgeCount());
        metrics.put("density", truthGraph.computeGraphDensity());
        metrics.put("transactionsProcessed", transactionCounter.get());

        // Top risk nodes
        List<EntityNode> risky = truthGraph.findHighRiskNodes(0.0);
        JSONArray topRiskArr = new JSONArray();
        int riskLimit = Math.min(10, risky.size());
        for (int i = 0; i < riskLimit; i++) {
            EntityNode n = risky.get(i);
            JSONObject entry = new JSONObject();
            entry.put("entityId", n.getEntityId());
            entry.put("riskMagnitude", n.computeRiskMagnitude());
            entry.put("extendedRiskMagnitude", n.computeExtendedRiskMagnitude());
            topRiskArr.put(entry);
        }
        metrics.put("topRiskNodes", topRiskArr);

        // PageRank top entities (run 20 iterations with damping 0.85)
        Map<Long, Double> pageRank = GraphMathEngine.computePageRank(truthGraph, 20, 0.85);
        JSONArray prArr = new JSONArray();
        pageRank.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> {
                    JSONObject prEntry = new JSONObject();
                    prEntry.put("entityId", e.getKey());
                    prEntry.put("pageRank", e.getValue());
                    prArr.put(prEntry);
                });
        metrics.put("topPageRank", prArr);

        // Convergence metrics (for Partner B)
        JSONObject convergence = new JSONObject();
        convergence.put("hypothesisAccuracy",
                1.0 - hypothesisGraph.compareWithTruth(truthGraph));
        convergence.put("mathSpeculationAccuracy", correctionDeltas.getMathSpeculationAccuracy());
        convergence.put("dlSpeculationAccuracy", correctionDeltas.getDlSpeculationAccuracy());
        convergence.put("avgDeltaNorm", correctionDeltas.getRunningAvgDeltaNorm());
        convergence.put("totalCorrections", correctionDeltas.getTotalCorrections());
        convergence.put("deltaBufferSize", correctionDeltas.getCurrentBufferSize());
        convergence.put("deltaMemoryBytes", correctionDeltas.estimatedMemoryBytes());
        metrics.put("convergenceMetrics", convergence);

        // Network stats
        JSONObject networkStats = new JSONObject();
        networkStats.put("communityCount", networkEngine.getCommunityCount());
        metrics.put("networkStats", networkStats);

        return metrics;
    }

    // ---- New endpoints for Partner B contract ----

    /**
     * Returns GNN embeddings for all nodes. Partner B consumes this for
     * Neo4j node properties and Spark community detection.
     */
    public JSONObject getEmbeddings() {
        Map<Long, double[]> embeddings = gnnPlugin.computeAllEmbeddings(truthGraph);

        JSONObject result = new JSONObject();
        result.put("embeddingDimension", gnnPlugin.getEmbeddingDimension());
        result.put("nodeCount", embeddings.size());

        JSONArray nodesArr = new JSONArray();
        for (Map.Entry<Long, double[]> entry : embeddings.entrySet()) {
            JSONObject nodeJson = new JSONObject();
            nodeJson.put("entityId", entry.getKey());
            JSONArray embArr = new JSONArray();
            for (double v : entry.getValue()) {
                embArr.put(v);
            }
            nodeJson.put("embedding", embArr);
            nodesArr.put(nodeJson);
        }
        result.put("nodes", nodesArr);

        return result;
    }

    /**
     * Returns correction deltas since a given transaction sequence number.
     * Partner B uses this to track hypothesis accuracy over time and detect drift.
     */
    public JSONObject getDeltas(long sinceTxnSequence) {
        List<CorrectionDelta> deltas = correctionDeltas.getDeltasSince(sinceTxnSequence);

        JSONObject result = new JSONObject();
        result.put("totalCorrections", correctionDeltas.getTotalCorrections());
        result.put("requestedSince", sinceTxnSequence);
        result.put("returnedCount", deltas.size());

        JSONArray deltasArr = new JSONArray();
        for (CorrectionDelta d : deltas) {
            JSONObject dj = new JSONObject();
            dj.put("entityId", d.getEntityId());
            dj.put("txnSequence", d.getTxnSequence());
            dj.put("deltaNorm", d.getDeltaNorm());
            dj.put("changedDims", d.getChangedDimensionCount());
            dj.put("source", d.getSpeculationSource());
            dj.put("gain", d.getAppliedGain());

            // Include the full delta vector for Partner B's batch analysis
            double[] fullDelta = d.toFullDelta();
            JSONArray deltaVec = new JSONArray();
            for (double v : fullDelta) {
                deltaVec.put(v);
            }
            dj.put("deltaVector", deltaVec);
            deltasArr.put(dj);
        }
        result.put("deltas", deltasArr);

        return result;
    }

    /**
     * Returns GAN anomaly scores: edges that look real but aren't in the truth graph.
     * Partner B flags these in Neo4j for fraud ring investigation.
     */
    public JSONObject getAnomalies() {
        List<GANPluginInterface.ScoredEdge> candidates =
                ganPlugin.findAnomalyCandidates(truthGraph, hypothesisGraph, GAN_ANOMALY_THRESHOLD);

        JSONObject result = new JSONObject();
        result.put("threshold", GAN_ANOMALY_THRESHOLD);
        result.put("candidateCount", candidates.size());

        JSONArray arr = new JSONArray();
        int limit = Math.min(50, candidates.size());
        for (int i = 0; i < limit; i++) {
            GANPluginInterface.ScoredEdge se = candidates.get(i);
            JSONObject ej = new JSONObject();
            ej.put("sourceId", se.edge().getSourceId());
            ej.put("targetId", se.edge().getTargetId());
            ej.put("weight", se.edge().getWeight());
            ej.put("discriminatorScore", se.score());
            ej.put("isInTruth", truthGraph.hasEdge(se.edge().getSourceId(), se.edge().getTargetId()));
            arr.put(ej);
        }
        result.put("anomalies", arr);

        return result;
    }

    /**
     * Resets all graph state. Intended for testing.
     */
    public void resetGraph() {
        truthGraph.clear();
        hypothesisGraph.clear();
        correctionDeltas.clear();
        kalmanEstimator.clear();
        networkEngine.clear();
        transactionCounter.set(0);
    }
}
