package org.frauddetection.graph;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-entity adaptive Kalman gain estimator.
 * <p>
 * The Kalman gain K_t(v) for entity v balances trust between the hypothesis
 * prediction and the incoming observation:
 * <pre>
 *   K_t(v) = σ²_process(v) / (σ²_process(v) + σ²_obs(v))
 * </pre>
 * Where:
 * <ul>
 *   <li>σ²_process = variance of hypothesis prediction errors (tracked as EMA)</li>
 *   <li>σ²_obs = measurement noise estimate (derived from historical transaction noise)</li>
 * </ul>
 * <p>
 * High K → trust observations more (hypothesis was wrong, correct aggressively).
 * Low K → trust hypothesis more (observation might be noise).
 * <p>
 * When no history exists for an entity, the gain defaults to 0.5 (equal trust).
 */
public class KalmanGainEstimator {

    /** Default gain when no history is available. */
    private static final double DEFAULT_GAIN = 0.5;

    /** Exponential moving average smoothing factor. */
    private static final double ALPHA = 0.1;

    /** Minimum gain to prevent hypothesis from becoming permanently stuck. */
    private static final double MIN_GAIN = 0.05;

    /** Maximum gain to prevent wild overcorrection. */
    private static final double MAX_GAIN = 0.95;

    /** Per-entity state for gain estimation. */
    private final ConcurrentHashMap<Long, GainState> entityStates = new ConcurrentHashMap<>();

    /**
     * Computes the adaptive Kalman gain for an entity given the latest prediction error.
     *
     * @param entityId  the entity
     * @param deltaNorm L2 norm of the correction delta (prediction error)
     * @return gain in [MIN_GAIN, MAX_GAIN]
     */
    public double computeGain(long entityId, double deltaNorm) {
        GainState state = entityStates.computeIfAbsent(entityId, k -> new GainState());

        synchronized (state) {
            // Update process variance estimate (EMA of squared prediction errors)
            state.processVariance = (1 - ALPHA) * state.processVariance
                    + ALPHA * (deltaNorm * deltaNorm);

            // Compute gain
            double denominator = state.processVariance + state.observationNoise;
            double gain;
            if (denominator < 1e-10) {
                gain = DEFAULT_GAIN;
            } else {
                gain = state.processVariance / denominator;
            }

            // Clamp to [MIN_GAIN, MAX_GAIN]
            gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, gain));

            // Track history
            state.lastGain = gain;
            state.updateCount++;

            return gain;
        }
    }

    /**
     * Updates the observation noise estimate for an entity.
     * Called when we have ground-truth information about measurement quality.
     *
     * @param entityId       the entity
     * @param observedNoise  estimated noise magnitude of the observation
     */
    public void updateObservationNoise(long entityId, double observedNoise) {
        GainState state = entityStates.computeIfAbsent(entityId, k -> new GainState());
        synchronized (state) {
            state.observationNoise = (1 - ALPHA) * state.observationNoise
                    + ALPHA * (observedNoise * observedNoise);
        }
    }

    /**
     * Returns the current gain for an entity without updating it.
     */
    public double getCurrentGain(long entityId) {
        GainState state = entityStates.get(entityId);
        return (state != null) ? state.lastGain : DEFAULT_GAIN;
    }

    /**
     * Returns the process variance estimate for an entity.
     */
    public double getProcessVariance(long entityId) {
        GainState state = entityStates.get(entityId);
        return (state != null) ? state.processVariance : 0.0;
    }

    /**
     * Returns the observation noise estimate for an entity.
     */
    public double getObservationNoise(long entityId) {
        GainState state = entityStates.get(entityId);
        return (state != null) ? state.observationNoise : 1.0;
    }

    /**
     * Returns the number of update steps for an entity.
     */
    public long getUpdateCount(long entityId) {
        GainState state = entityStates.get(entityId);
        return (state != null) ? state.updateCount : 0;
    }

    /**
     * Returns the number of entities being tracked.
     */
    public int getTrackedEntityCount() {
        return entityStates.size();
    }

    /** Clears all state (for testing). */
    public void clear() {
        entityStates.clear();
    }

    // ---- Internal state ----

    private static class GainState {
        /** EMA of squared prediction errors. */
        double processVariance = 1.0;

        /** EMA of squared observation noise. */
        double observationNoise = 1.0;

        /** Last computed gain. */
        double lastGain = DEFAULT_GAIN;

        /** Total number of updates. */
        long updateCount = 0;
    }
}
