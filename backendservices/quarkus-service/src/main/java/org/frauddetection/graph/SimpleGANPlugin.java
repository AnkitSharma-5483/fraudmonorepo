package org.frauddetection.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default GAN implementation providing heuristic-based edge generation
 * and discriminator scoring.
 * <p>
 * This is a statistics-based stub: the "generator" samples edges based on
 * the truth graph's degree distribution and edge weight distribution.
 * The "discriminator" scores edges based on structural plausibility
 * (source/target degree, weight relative to neighbors, graph context).
 * <p>
 * Replace with a real adversarial network (PyTorch-served via REST or ONNX)
 * for production use.
 * <p>
 * <b>Anomaly detection</b>:
 * <ul>
 *   <li>High discriminator score + not in truth = fraud candidate</li>
 *   <li>Low discriminator score + in truth = anomalous real edge</li>
 * </ul>
 * <p>
 * <b>What-if scenarios</b>: Generates edge sets that seem plausible based on
 * the truth distribution, for batch evaluation by Partner B's Spark.
 */
public class SimpleGANPlugin implements GANPluginInterface {

    /** Pseudo-random state for deterministic generation. */
    private long randomState;

    public SimpleGANPlugin() {
        this(System.nanoTime());
    }

    public SimpleGANPlugin(long seed) {
        this.randomState = seed;
    }

    @Override
    public List<GraphEdge> generateSyntheticEdges(TruthGraph graph, int count) {
        List<GraphEdge> synthetic = new ArrayList<>();
        if (graph.getNodeCount() < 2) return synthetic;

        // Collect nodes and compute weight statistics
        List<Long> nodeIds = new ArrayList<>();
        double totalWeight = 0.0;
        long totalEdges = 0;
        for (EntityNode node : graph.allNodes()) {
            nodeIds.add(node.getEntityId());
        }
        for (Long srcId : graph.allSourceIds()) {
            for (GraphEdge e : graph.getNeighbors(srcId)) {
                totalWeight += e.getWeight();
                totalEdges++;
            }
        }
        double avgWeight = (totalEdges > 0) ? totalWeight / totalEdges : 1.0;

        // Generate synthetic edges by sampling source/target from node distribution
        for (int i = 0; i < count; i++) {
            long sourceId = nodeIds.get(nextInt(nodeIds.size()));
            long targetId = nodeIds.get(nextInt(nodeIds.size()));
            if (sourceId == targetId) continue;

            // Weight drawn from approximate distribution (log-normal-ish)
            double weight = avgWeight * (0.5 + nextDouble() * 2.0);
            synthetic.add(new GraphEdge(sourceId, targetId, weight));
        }

        return synthetic;
    }

    @Override
    public double discriminate(GraphEdge edge, TruthGraph graph) {
        // Heuristic discriminator: score based on structural plausibility
        EntityNode source = graph.getNode(edge.getSourceId());
        EntityNode target = graph.getNode(edge.getTargetId());

        if (source == null || target == null) return 0.1; // unknown nodes → low score

        double score = 0.0;

        // Factor 1: Source degree (well-connected sources are more plausible)
        int srcDegree = graph.getNeighbors(edge.getSourceId()).size();
        score += 0.25 * Math.min(srcDegree / 10.0, 1.0);

        // Factor 2: Target degree
        int tgtDegree = graph.getNeighbors(edge.getTargetId()).size();
        score += 0.15 * Math.min(tgtDegree / 10.0, 1.0);

        // Factor 3: Behavioral similarity (cosine of extended state vectors)
        double similarity = GraphMathEngine.cosineSimilarity(
                source.getExtendedStateVectorRef(), target.getExtendedStateVectorRef());
        score += 0.30 * (similarity + 1.0) / 2.0; // normalize from [-1,1] to [0,1]

        // Factor 4: Edge weight plausibility
        double avgNeighborWeight = 0.0;
        List<GraphEdge> srcNeighbors = graph.getNeighbors(edge.getSourceId());
        if (!srcNeighbors.isEmpty()) {
            for (GraphEdge e : srcNeighbors) {
                avgNeighborWeight += e.getWeight();
            }
            avgNeighborWeight /= srcNeighbors.size();
            double weightRatio = (avgNeighborWeight > 0)
                    ? Math.min(edge.getWeight() / avgNeighborWeight, 2.0) / 2.0
                    : 0.5;
            score += 0.30 * weightRatio;
        } else {
            score += 0.15;
        }

        return Math.max(0.0, Math.min(1.0, score)); // clamp to [0,1]
    }

    @Override
    public Map<String, Double> batchDiscriminate(HypothesisGraph hypothesis, TruthGraph truth) {
        Map<String, Double> scores = new HashMap<>();
        for (GraphEdge e : hypothesis.getSpeculativeEdges()) {
            String key = e.getSourceId() + "->" + e.getTargetId();
            scores.put(key, discriminate(e, truth));
        }
        return scores;
    }

    @Override
    public List<List<GraphEdge>> generateScenarios(TruthGraph graph, int numScenarios, int edgesPerScenario) {
        List<List<GraphEdge>> scenarios = new ArrayList<>();
        for (int s = 0; s < numScenarios; s++) {
            scenarios.add(generateSyntheticEdges(graph, edgesPerScenario));
        }
        return scenarios;
    }

    @Override
    public List<ScoredEdge> findAnomalyCandidates(TruthGraph graph, HypothesisGraph hypothesis, double minScore) {
        List<ScoredEdge> candidates = new ArrayList<>();

        // Check speculative edges: high discriminator score + not in truth = anomaly candidate
        for (GraphEdge e : hypothesis.getSpeculativeEdges()) {
            if (!graph.hasEdge(e.getSourceId(), e.getTargetId())) {
                double score = discriminate(e, graph);
                if (score >= minScore) {
                    candidates.add(new ScoredEdge(e, score));
                }
            }
        }

        // Also generate synthetic edges and check them
        List<GraphEdge> synthetics = generateSyntheticEdges(graph, 50);
        for (GraphEdge e : synthetics) {
            if (!graph.hasEdge(e.getSourceId(), e.getTargetId())) {
                double score = discriminate(e, graph);
                if (score >= minScore) {
                    candidates.add(new ScoredEdge(e, score));
                }
            }
        }

        // Sort by score descending
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        return candidates;
    }

    // ---- Pseudo-random helpers (deterministic, no java.util.Random alloc) ----

    private int nextInt(int bound) {
        randomState = randomState * 6364136223846793005L + 1442695040888963407L;
        int val = (int) ((randomState >>> 33) % bound);
        return (val < 0) ? -val : val;
    }

    private double nextDouble() {
        randomState = randomState * 6364136223846793005L + 1442695040888963407L;
        return ((randomState >>> 33) & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }
}
