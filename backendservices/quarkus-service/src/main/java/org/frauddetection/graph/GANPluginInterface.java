package org.frauddetection.graph;

import java.util.List;
import java.util.Map;

/**
 * Interface for Generative Adversarial Network (GAN) integration.
 * <p>
 * A GAN plugin generates synthetic edges to augment training data and
 * provides a discriminator score indicating how "real" a given edge appears
 * relative to the observed truth graph.
 * <p>
 * <b>Anomaly detection insight</b>:
 * <ul>
 *   <li>Edges that fool the discriminator (score > 0.5) but don't exist in truth
 *       → <b>prime fraud candidates</b> (should exist based on patterns but don't)</li>
 *   <li>Edges in truth with low discriminator score (< 0.5)
 *       → <b>anomalous real edges</b> (unusual transaction patterns)</li>
 * </ul>
 * <p>
 * <b>What-if scenario generation</b>: The generator produces multiple synthetic
 * edge sets. Each set is overlaid onto the hypothesis graph and evaluated for
 * impact on topology metrics (PageRank, hotspots, community structure).
 * Partner B's Spark validates scenarios against historical patterns at batch scale.
 */
public interface GANPluginInterface {

    /**
     * Generates synthetic (fake) edges that mimic the distribution of the
     * truth graph.  Useful for data augmentation and anomaly detection.
     *
     * @param graph the truth graph used as the real-data distribution
     * @param count number of synthetic edges to generate
     * @return list of generated edges
     */
    List<GraphEdge> generateSyntheticEdges(TruthGraph graph, int count);

    /**
     * Discriminator: estimates the probability that the given edge is real
     * (i.e., drawn from the truth graph) versus synthetic.
     *
     * @param edge  the edge to evaluate
     * @param graph the truth graph for context
     * @return probability in [0, 1]; 1 = almost certainly real
     */
    double discriminate(GraphEdge edge, TruthGraph graph);

    /**
     * Batch-discriminates all edges in the hypothesis graph, returning
     * anomaly scores. Edges with high score absent from truth are fraud candidates;
     * edges with low score present in truth are anomalous.
     *
     * @param hypothesis the hypothesis graph with speculative edges
     * @param truth      the truth graph for comparison
     * @return map of "sourceId->targetId" → discriminator score
     */
    Map<String, Double> batchDiscriminate(HypothesisGraph hypothesis, TruthGraph truth);

    /**
     * Generates what-if scenarios: each scenario is a set of synthetic edges
     * that could plausibly exist. The scenarios are meant to be evaluated for
     * their impact on graph metrics by the analytics pipeline and validated
     * by Partner B's Spark at batch scale.
     *
     * @param graph    the truth graph as distribution source
     * @param numScenarios number of scenarios to generate
     * @param edgesPerScenario edges per scenario
     * @return list of scenarios, each being a list of synthetic edges
     */
    List<List<GraphEdge>> generateScenarios(TruthGraph graph, int numScenarios, int edgesPerScenario);

    /**
     * Returns anomaly candidates: edges that the discriminator scores as "real-looking"
     * but that do NOT exist in the truth graph.
     *
     * @param graph     the truth graph
     * @param hypothesis the hypothesis graph
     * @param minScore  minimum discriminator score to qualify
     * @return list of anomalous edges with their scores
     */
    List<ScoredEdge> findAnomalyCandidates(TruthGraph graph, HypothesisGraph hypothesis, double minScore);

    /**
     * An edge paired with its discriminator score.
     */
    record ScoredEdge(GraphEdge edge, double score) {}
}
