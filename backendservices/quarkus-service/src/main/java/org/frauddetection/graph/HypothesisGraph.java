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
 * Experimental / speculative graph layer that maintains hypothesized edges
 * which have not yet been confirmed by the truth graph.
 * <p>
 * Supports a predictor–corrector cycle:
 * <ol>
 *   <li><b>Predictor</b>: project future edges based on observed patterns.</li>
 *   <li><b>Corrector</b>: prune speculative edges that deviate from truth.</li>
 * </ol>
 */
public class HypothesisGraph {

    /** Speculative adjacency: sourceId → list of speculative edges. */
    private final ConcurrentHashMap<Long, List<GraphEdge>> speculativeAdj = new ConcurrentHashMap<>();

    // ---- Edge management ----

    /**
     * Adds a speculative edge with the given confidence as its weight.
     * If an edge between source and target already exists, its weight is updated
     * to the maximum of the old and new confidence.
     */
    public void addSpeculativeEdge(long source, long target, double confidence) {
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
     * Removes speculative edges whose weight (confidence) is below the threshold.
     */
    public void pruneWeakEdges(double minConfidence) {
        for (List<GraphEdge> edges : speculativeAdj.values()) {
            synchronized (edges) {
                Iterator<GraphEdge> it = edges.iterator();
                while (it.hasNext()) {
                    if (it.next().getWeight() < minConfidence) {
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

    // ---- Predictor / Corrector cycle ----

    /**
     * <b>Predictor step</b>: given a new transaction, speculate that the source
     * account may transact with the same merchant's neighbors in the truth graph
     * (if we had access) or simply add a low-confidence speculative edge for the
     * observed source → merchant pair.  This lets downstream corrector validate.
     */
    public void predictorStep(TransactionData txn) {
        long sourceId = txn.getCcNum();
        // Derive merchant id the same way the truth graph does
        long merchantId = deriveMerchantId(txn);

        // Speculate: source will transact again with this merchant
        double confidence = Math.min(txn.getAmt() / 1000.0, 1.0); // normalized confidence
        addSpeculativeEdge(sourceId, merchantId, confidence);

        // Speculate: nearby merchants (simple perturbation of merchant id)
        // This is a lightweight heuristic – real system would use embeddings
        long nearbyMerchant = merchantId ^ 0xFFFL; // flip low bits for a "nearby" merchant
        addSpeculativeEdge(sourceId, nearbyMerchant, confidence * 0.3);
    }

    /**
     * <b>Corrector step</b>: compare speculative edges against the truth graph
     * and remove those with high deviation (not confirmed by reality).
     */
    public void correctorStep(TruthGraph truth) {
        double deviation = compareWithTruth(truth);

        // Adaptive pruning: higher deviation → more aggressive pruning
        double pruneThreshold = 0.1 + deviation * 0.5;
        pruneWeakEdges(pruneThreshold);
    }

    // ---- JSON summary ----

    /**
     * Returns a JSON summary with edge count, deviation placeholder, and edges.
     */
    public JSONObject toSummaryJson() {
        List<GraphEdge> edges = getSpeculativeEdges();

        JSONObject json = new JSONObject();
        json.put("speculativeEdgeCount", edges.size());

        JSONArray edgeArr = new JSONArray();
        int limit = Math.min(10, edges.size()); // cap for readability
        for (int i = 0; i < limit; i++) {
            GraphEdge e = edges.get(i);
            JSONObject ej = new JSONObject();
            ej.put("source", e.getSourceId());
            ej.put("target", e.getTargetId());
            ej.put("confidence", e.getWeight());
            edgeArr.put(ej);
        }
        json.put("topEdges", edgeArr);
        return json;
    }

    /** Clears all speculative edges (for testing). */
    public void clear() {
        speculativeAdj.clear();
    }

    // ---- Internal ----

    /** Must match TruthGraph.deriveMerchantId for consistency. */
    private static long deriveMerchantId(TransactionData txn) {
        long latBits = Double.doubleToLongBits(txn.getMerchLat());
        long lonBits = Double.doubleToLongBits(txn.getMerchLon());
        return Math.abs(latBits * 31 + lonBits);
    }
}
