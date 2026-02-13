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
 * Quarkus CDI service that orchestrates the graph analytical pipeline.
 * <p>
 * Owns the {@link TruthGraph} and {@link HypothesisGraph} instances and
 * exposes high-level operations consumed by REST endpoints and other services.
 */
@ApplicationScoped
public class GraphAnalyticsService {

    /** The confirmed-transaction graph. */
    private final TruthGraph truthGraph = new TruthGraph();

    /** The speculative / hypothesis graph. */
    private final HypothesisGraph hypothesisGraph = new HypothesisGraph();

    /** Total transactions processed through the graph pipeline. */
    private final AtomicLong transactionCounter = new AtomicLong(0);

    // ---- Main pipeline ----

    /**
     * Processes a single transaction through the full graph pipeline:
     * <ol>
     *   <li>Update the truth graph with the confirmed transaction.</li>
     *   <li>Run the predictor step on the hypothesis graph.</li>
     *   <li>Run the corrector step to reconcile hypothesis with truth.</li>
     *   <li>Compute the fraud hotspot score for the source entity.</li>
     * </ol>
     *
     * @return JSON object with graph-derived metrics for this transaction
     */
    public JSONObject processTransaction(TransactionData txn) {
        long txnNum = transactionCounter.incrementAndGet();

        // 1. Truth graph update
        truthGraph.addTransaction(txn);

        // 2. Predictor: speculate future edges
        hypothesisGraph.predictorStep(txn);

        // 3. Corrector: prune speculations that diverge from truth
        hypothesisGraph.correctorStep(truthGraph);

        // 4. Fraud hotspot score for the source entity
        long sourceId = txn.getCcNum();
        double hotspot = GraphMathEngine.fraudHotspotScore(truthGraph, sourceId);

        // 5. Assemble response
        EntityNode sourceNode = truthGraph.getNode(sourceId);

        JSONObject result = new JSONObject();
        result.put("transactionNumber", txnNum);
        result.put("entityId", sourceId);
        result.put("fraudHotspotScore", hotspot);
        result.put("riskMagnitude", sourceNode != null ? sourceNode.computeRiskMagnitude() : 0.0);
        result.put("graphNodeCount", truthGraph.getNodeCount());
        result.put("graphEdgeCount", truthGraph.getEdgeCount());
        result.put("hypothesisDeviation", hypothesisGraph.compareWithTruth(truthGraph));
        return result;
    }

    // ---- Query methods ----

    /**
     * Returns a full summary of both truth and hypothesis graphs.
     */
    public JSONObject getGraphState() {
        JSONObject state = new JSONObject();
        state.put("transactionsProcessed", transactionCounter.get());
        state.put("truthGraph", truthGraph.toSummaryJson());
        state.put("hypothesisGraph", hypothesisGraph.toSummaryJson());
        return state;
    }

    /**
     * Returns detailed state for a single entity.
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
        json.put("lastUpdateTime", node.getLastUpdateTime());

        // State vector breakdown
        double[] sv = node.getStateVector();
        JSONObject stateJson = new JSONObject();
        stateJson.put("txnVolume", sv[0]);
        stateJson.put("avgAmount", sv[1]);
        stateJson.put("riskScore", sv[2]);
        stateJson.put("velocity", sv[3]);
        stateJson.put("diversity", sv[4]);
        json.put("stateVector", stateJson);

        // Projected state (unit sphere)
        double[] proj = GraphMathEngine.projectState(node);
        JSONArray projArr = new JSONArray();
        for (double v : proj) {
            projArr.put(v);
        }
        json.put("projectedState", projArr);

        // Neighbors
        List<GraphEdge> neighbors = truthGraph.getNeighbors(entityId);
        json.put("neighborCount", neighbors.size());
        json.put("fraudHotspotScore", GraphMathEngine.fraudHotspotScore(truthGraph, entityId));

        return json;
    }

    /**
     * Returns graph-wide analytical metrics including PageRank top entities.
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

        return metrics;
    }

    /**
     * Resets all graph state. Intended for testing.
     */
    public void resetGraph() {
        truthGraph.clear();
        hypothesisGraph.clear();
        transactionCounter.set(0);
    }
}
