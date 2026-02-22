package org.frauddetection.graph;

/**
 * Constants and utility methods for the extended 17-dimensional state vector.
 * <p>
 * The base 5-dim vector (txnVolume, avgAmount, riskScore, velocity, diversity)
 * is augmented with 12 ML/DL-derived features across three categories:
 * temporal patterns, behavioral anomalies, and network-derived features.
 * <p>
 * Layout:
 * <pre>
 *   [0]  txnVolume              (base)
 *   [1]  avgAmount              (base)
 *   [2]  riskScore              (base)
 *   [3]  velocity               (base)
 *   [4]  diversity              (base)
 *   [5]  predictedNextTxnTime   (temporal)
 *   [6]  burstProbability       (temporal)
 *   [7]  periodicityScore       (temporal)
 *   [8]  timeOfDayRisk          (temporal)
 *   [9]  isolationScore         (behavioral)
 *   [10] clusterDeviation       (behavioral)
 *   [11] spendingDrift          (behavioral — CUSUM)
 *   [12] merchantAffinityShift  (behavioral)
 *   [13] communityId            (network)
 *   [14] bridgeScore            (network)
 *   [15] localClusteringCoeff   (network)
 *   [16] kHopRiskPropagation    (network — heat diffusion)
 * </pre>
 */
public final class ExtendedStateVector {

    private ExtendedStateVector() {
        // utility class
    }

    // ---- Dimensions ----

    /** Base state vector dimensionality (backward-compatible). */
    public static final int BASE_DIM = 5;

    /** Full extended state vector dimensionality. */
    public static final int EXTENDED_DIM = 17;

    // ---- Base indices (0–4) ----

    public static final int IDX_TXN_VOLUME = 0;
    public static final int IDX_AVG_AMOUNT = 1;
    public static final int IDX_RISK_SCORE = 2;
    public static final int IDX_VELOCITY = 3;
    public static final int IDX_DIVERSITY = 4;

    // ---- Temporal indices (5–8) ----

    public static final int IDX_PREDICTED_NEXT_TXN_TIME = 5;
    public static final int IDX_BURST_PROBABILITY = 6;
    public static final int IDX_PERIODICITY_SCORE = 7;
    public static final int IDX_TIME_OF_DAY_RISK = 8;

    // ---- Behavioral indices (9–12) ----

    public static final int IDX_ISOLATION_SCORE = 9;
    public static final int IDX_CLUSTER_DEVIATION = 10;
    public static final int IDX_SPENDING_DRIFT = 11;
    public static final int IDX_MERCHANT_AFFINITY_SHIFT = 12;

    // ---- Network indices (13–16) ----

    public static final int IDX_COMMUNITY_ID = 13;
    public static final int IDX_BRIDGE_SCORE = 14;
    public static final int IDX_LOCAL_CLUSTERING_COEFF = 15;
    public static final int IDX_K_HOP_RISK_PROPAGATION = 16;

    // ---- Category ranges ----

    /** Start index (inclusive) of temporal features. */
    public static final int TEMPORAL_START = 5;
    /** End index (exclusive) of temporal features. */
    public static final int TEMPORAL_END = 9;

    /** Start index (inclusive) of behavioral features. */
    public static final int BEHAVIORAL_START = 9;
    /** End index (exclusive) of behavioral features. */
    public static final int BEHAVIORAL_END = 13;

    /** Start index (inclusive) of network features. */
    public static final int NETWORK_START = 13;
    /** End index (exclusive) of network features. */
    public static final int NETWORK_END = 17;

    // ---- Dimension names (for JSON/logging) ----

    /** Human-readable names for all 17 dimensions. */
    public static final String[] DIMENSION_NAMES = {
            "txnVolume", "avgAmount", "riskScore", "velocity", "diversity",
            "predictedNextTxnTime", "burstProbability", "periodicityScore", "timeOfDayRisk",
            "isolationScore", "clusterDeviation", "spendingDrift", "merchantAffinityShift",
            "communityId", "bridgeScore", "localClusteringCoeff", "kHopRiskPropagation"
    };

    // ---- Utility methods ----

    /**
     * Computes a bitmask indicating which dimensions differ between two vectors
     * by more than the given epsilon. Used for delta compression.
     *
     * @param a       first vector (length must be EXTENDED_DIM)
     * @param b       second vector (length must be EXTENDED_DIM)
     * @param epsilon minimum absolute difference to count as changed
     * @return bitmask where bit i is set if |a[i] - b[i]| > epsilon
     */
    public static int computeChangeBitmask(double[] a, double[] b, double epsilon) {
        int mask = 0;
        int dim = Math.min(a.length, Math.min(b.length, EXTENDED_DIM));
        for (int i = 0; i < dim; i++) {
            if (Math.abs(a[i] - b[i]) > epsilon) {
                mask |= (1 << i);
            }
        }
        return mask;
    }

    /**
     * Counts the number of set bits in a bitmask (number of changed dimensions).
     */
    public static int popCount(int bitmask) {
        return Integer.bitCount(bitmask);
    }

    /**
     * Packs only the changed dimensions (identified by bitmask) into a compact array.
     *
     * @param delta   full delta vector (length EXTENDED_DIM)
     * @param bitmask bitmask of changed dimensions
     * @return compact array containing only the non-zero delta values
     */
    public static double[] packDelta(double[] delta, int bitmask) {
        double[] packed = new double[Integer.bitCount(bitmask)];
        int idx = 0;
        for (int i = 0; i < EXTENDED_DIM && i < delta.length; i++) {
            if ((bitmask & (1 << i)) != 0) {
                packed[idx++] = delta[i];
            }
        }
        return packed;
    }

    /**
     * Unpacks a compact delta array back into a full-length vector.
     *
     * @param packed  compact array from {@link #packDelta}
     * @param bitmask bitmask used during packing
     * @return full-length vector (length EXTENDED_DIM) with zeros for unchanged dims
     */
    public static double[] unpackDelta(double[] packed, int bitmask) {
        double[] full = new double[EXTENDED_DIM];
        int idx = 0;
        for (int i = 0; i < EXTENDED_DIM; i++) {
            if ((bitmask & (1 << i)) != 0) {
                full[i] = packed[idx++];
            }
        }
        return full;
    }

    /**
     * Computes the L2 norm of a vector.
     */
    public static double l2Norm(double[] v) {
        double sumSq = 0.0;
        for (double x : v) {
            sumSq += x * x;
        }
        return Math.sqrt(sumSq);
    }

    /**
     * Computes element-wise difference: result[i] = a[i] - b[i].
     */
    public static double[] subtract(double[] a, double[] b) {
        int dim = Math.min(a.length, b.length);
        double[] result = new double[dim];
        for (int i = 0; i < dim; i++) {
            result[i] = a[i] - b[i];
        }
        return result;
    }

    /**
     * Applies a scalar-multiplied delta to a state vector: result[i] = state[i] + gain * delta[i].
     */
    public static double[] applyGainedDelta(double[] state, double[] delta, double gain) {
        int dim = Math.min(state.length, delta.length);
        double[] result = new double[Math.max(state.length, EXTENDED_DIM)];
        System.arraycopy(state, 0, result, 0, state.length);
        for (int i = 0; i < dim; i++) {
            result[i] += gain * delta[i];
        }
        return result;
    }
}
