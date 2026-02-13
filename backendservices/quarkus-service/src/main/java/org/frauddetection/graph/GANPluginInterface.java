package org.frauddetection.graph;

import java.util.List;

/**
 * Interface for future Generative Adversarial Network (GAN) integration.
 * <p>
 * A GAN plugin can generate synthetic edges to augment training data and
 * provide a discriminator score indicating how "real" a given edge appears
 * relative to the observed truth graph.
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
}
