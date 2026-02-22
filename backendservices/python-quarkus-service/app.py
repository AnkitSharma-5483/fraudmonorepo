import os
import math
import pickle
import hashlib
import pandas as pd
from flask import Flask, request, jsonify

# Paths
MODEL_PATH = "model.pkl"  # Look in the current directory

# Loading the model
if not os.path.exists(MODEL_PATH):
    raise FileNotFoundError(f"Model file not found: {MODEL_PATH}")

print("Loading trained model...")
with open(MODEL_PATH, "rb") as file:
    model = pickle.load(file)

# Defined required fields (9 fields) a other fields are dropped due to being of object data type
REQUIRED_FIELDS = ["cc_num", "amt", "zip", "lat", "long", "city_pop", "unix_time", "merch_lat", "merch_long"]

# Extended feature dimension names (indices 5-12 from the 17-dim state vector)
# Indices 0-4 are the base state; 13-16 are network features computed in Java.
TEMPORAL_BEHAVIORAL_DIMS = [
    "predictedNextTxnTime",    # dim 5: EMA-predicted next transaction timestamp
    "burstProbability",        # dim 6: probability of burst behavior (short inter-txn)
    "periodicityScore",        # dim 7: how periodic the transaction pattern is
    "timeOfDayRisk",           # dim 8: risk score based on hour-of-day
    "isolationScore",          # dim 9: how isolated the entity is behaviorally
    "clusterDeviation",        # dim 10: deviation from typical cluster behavior
    "merchantAffinityShift",   # dim 11: change in merchant category affinity
    "spendingVelocity",        # dim 12: rate of spending acceleration
]

# Flask app
app = Flask(__name__)


# ---- Heuristic feature engines for temporal/behavioral dimensions ----

def _compute_burst_probability(inter_txn_intervals):
    """
    Estimate burst probability from recent inter-transaction intervals.
    Burst = many transactions in short succession.
    P(burst) = fraction of intervals < median / 3.
    """
    if not inter_txn_intervals or len(inter_txn_intervals) < 2:
        return 0.0
    sorted_intervals = sorted(inter_txn_intervals)
    median = sorted_intervals[len(sorted_intervals) // 2]
    if median <= 0:
        return 1.0
    burst_threshold = median / 3.0
    burst_count = sum(1 for iv in inter_txn_intervals if iv < burst_threshold)
    return burst_count / len(inter_txn_intervals)


def _compute_periodicity_score(inter_txn_intervals):
    """
    Estimate periodicity using coefficient of variation of inter-txn intervals.
    Low CV → regular/periodic; high CV → irregular.
    Score = 1 / (1 + CV) so periodic patterns score high.
    """
    if not inter_txn_intervals or len(inter_txn_intervals) < 3:
        return 0.5  # neutral
    mean_val = sum(inter_txn_intervals) / len(inter_txn_intervals)
    if mean_val <= 0:
        return 0.0
    variance = sum((x - mean_val) ** 2 for x in inter_txn_intervals) / len(inter_txn_intervals)
    std_val = math.sqrt(variance)
    cv = std_val / mean_val
    return 1.0 / (1.0 + cv)


def _compute_time_of_day_risk(unix_time):
    """
    Risk score based on hour of day.
    Higher risk for unusual hours (midnight-5am: high, 9am-5pm: low).
    Uses a sinusoidal model: risk = 0.5 + 0.5 * cos(2π * (hour - 2) / 24)
    Peak risk at 2am, lowest at 2pm.
    """
    hour = (unix_time // 3600) % 24
    return 0.5 + 0.5 * math.cos(2 * math.pi * (hour - 2) / 24.0)


def _compute_isolation_score(amt, city_pop, amounts_history):
    """
    Behavioral isolation: how much this entity's behavior deviates from
    the norm. Based on amount Z-score relative to history and inverse
    population density.
    """
    # Amount Z-score
    z_score = 0.0
    if amounts_history and len(amounts_history) >= 3:
        mean_amt = sum(amounts_history) / len(amounts_history)
        var_amt = sum((a - mean_amt) ** 2 for a in amounts_history) / len(amounts_history)
        std_amt = math.sqrt(var_amt) if var_amt > 0 else 1.0
        z_score = abs(amt - mean_amt) / std_amt

    # Population density factor (rural = more isolated)
    pop_factor = 1.0 / (1.0 + math.log1p(city_pop) / 15.0)

    # Combine: clamp to [0, 1]
    score = min(1.0, 0.6 * min(z_score / 3.0, 1.0) + 0.4 * pop_factor)
    return score


def _compute_cluster_deviation(amt, lat, lon, amounts_history, lat_history, lon_history):
    """
    Deviation from the entity's typical geographic and spending cluster.
    Uses simple centroid distance.
    """
    if not amounts_history or len(amounts_history) < 2:
        return 0.5  # neutral for new entities

    # Geographic centroid deviation
    mean_lat = sum(lat_history) / len(lat_history)
    mean_lon = sum(lon_history) / len(lon_history)
    geo_dist = math.sqrt((lat - mean_lat) ** 2 + (lon - mean_lon) ** 2)
    geo_dev = min(1.0, geo_dist / 5.0)  # normalize: 5 degrees = max deviation

    # Amount centroid deviation
    mean_amt = sum(amounts_history) / len(amounts_history)
    amt_dev = min(1.0, abs(amt - mean_amt) / (mean_amt + 1.0))

    return 0.5 * geo_dev + 0.5 * amt_dev


def _compute_merchant_affinity_shift(merch_lat, merch_lon, merch_history):
    """
    How much the current merchant location differs from the entity's
    typical merchant locations. Shift in affinity pattern.
    """
    if not merch_history or len(merch_history) < 2:
        return 0.0  # no shift for new entities

    mean_mlat = sum(m[0] for m in merch_history) / len(merch_history)
    mean_mlon = sum(m[1] for m in merch_history) / len(merch_history)
    dist = math.sqrt((merch_lat - mean_mlat) ** 2 + (merch_lon - mean_mlon) ** 2)
    return min(1.0, dist / 3.0)  # normalize


def _compute_spending_velocity(amounts_history, timestamps_history):
    """
    Rate of spending acceleration. Positive = spending increasing,
    negative compressed to [0,1] range.
    """
    if not amounts_history or len(amounts_history) < 3 or not timestamps_history or len(timestamps_history) < 3:
        return 0.5

    # Compute spending rate in recent vs older halves
    n = len(amounts_history)
    mid = n // 2
    recent_sum = sum(amounts_history[mid:])
    older_sum = sum(amounts_history[:mid])
    recent_time = max(1, timestamps_history[-1] - timestamps_history[mid])
    older_time = max(1, timestamps_history[mid] - timestamps_history[0])

    recent_rate = recent_sum / recent_time
    older_rate = older_sum / older_time

    if older_rate <= 0:
        return 0.5 if recent_rate <= 0 else 1.0

    ratio = recent_rate / older_rate
    # Map ratio to [0, 1]: ratio=1 → 0.5, ratio>1 → >0.5, ratio<1 → <0.5
    return min(1.0, max(0.0, 0.5 + 0.5 * math.tanh(ratio - 1.0)))


# ---- In-memory per-entity history (lightweight, capped) ----
# In production, this would be backed by a proper time-series store.
_entity_history = {}
_MAX_HISTORY = 100


def _get_or_create_history(cc_num):
    """Get or create a history buffer for an entity."""
    if cc_num not in _entity_history:
        _entity_history[cc_num] = {
            "amounts": [],
            "timestamps": [],
            "lats": [],
            "lons": [],
            "merch_locations": [],
            "inter_txn_intervals": [],
        }
    return _entity_history[cc_num]


def _update_history(hist, data):
    """Append current transaction data to history buffers (capped)."""
    hist["amounts"].append(data["amt"])
    hist["timestamps"].append(data["unix_time"])
    hist["lats"].append(data["lat"])
    hist["lons"].append(data["long"])
    hist["merch_locations"].append((data["merch_lat"], data["merch_long"]))

    if len(hist["timestamps"]) >= 2:
        interval = hist["timestamps"][-1] - hist["timestamps"][-2]
        hist["inter_txn_intervals"].append(max(0, interval))

    # Cap history
    for key in hist:
        if len(hist[key]) > _MAX_HISTORY:
            hist[key] = hist[key][-_MAX_HISTORY:]


# ---- Endpoints ----

@app.route("/predict", methods=["POST"])
def predict():
    try:
        data = request.get_json()

        # Validate input as test case
        if not all(field in data for field in REQUIRED_FIELDS):
            return jsonify({"error": "Missing required fields", "expected": REQUIRED_FIELDS}), 400

        # Converting input to DataFrame format (ensured correct order)
        df_input = pd.DataFrame([data])[REQUIRED_FIELDS]

        # Making prediction
        prediction = model.predict(df_input)[0]

        return jsonify({
            "prediction": int(prediction),
            "reason": "ML model detected potential fraud" if prediction == 1 else "Transaction looks safe."
        })

    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/predict-extended", methods=["POST"])
def predict_extended():
    """
    Predict the extended temporal/behavioral features (dims 5-12 of the 17-dim state vector).

    Accepts the same 9 required fields as /predict, plus optional entity history context.
    Returns the 8 computed feature values that the Java graph pipeline will merge into
    positions 5-12 of the extended state vector.

    The Quarkus service calls this endpoint during Stage 4 (ML ENRICHMENT) of the
    6-stage pipeline. The response is designed to be directly consumed by
    MLIntegrationInterface.predictExtendedState().
    """
    try:
        data = request.get_json()

        # Validate input
        if not all(field in data for field in REQUIRED_FIELDS):
            return jsonify({"error": "Missing required fields", "expected": REQUIRED_FIELDS}), 400

        cc_num = data["cc_num"]
        amt = float(data["amt"])
        lat = float(data["lat"])
        lon = float(data["long"])
        city_pop = int(data["city_pop"])
        unix_time = int(data["unix_time"])
        merch_lat = float(data["merch_lat"])
        merch_lon = float(data["merch_long"])

        # Get / update entity history
        hist = _get_or_create_history(cc_num)
        _update_history(hist, data)

        # -- Compute dims 5-8: Temporal features --

        # Dim 5: predictedNextTxnTime (EMA of inter-txn intervals → predicted next time)
        if hist["inter_txn_intervals"]:
            alpha = 0.2
            ema = hist["inter_txn_intervals"][0]
            for iv in hist["inter_txn_intervals"][1:]:
                ema = alpha * iv + (1 - alpha) * ema
            predicted_next = unix_time + ema
        else:
            predicted_next = unix_time + 86400  # default: 1 day

        # Dim 6: burstProbability
        burst_prob = _compute_burst_probability(hist["inter_txn_intervals"])

        # Dim 7: periodicityScore
        periodicity = _compute_periodicity_score(hist["inter_txn_intervals"])

        # Dim 8: timeOfDayRisk
        tod_risk = _compute_time_of_day_risk(unix_time)

        # -- Compute dims 9-12: Behavioral features --

        # Dim 9: isolationScore
        isolation = _compute_isolation_score(amt, city_pop, hist["amounts"])

        # Dim 10: clusterDeviation
        cluster_dev = _compute_cluster_deviation(
            amt, lat, lon, hist["amounts"], hist["lats"], hist["lons"]
        )

        # Dim 11: merchantAffinityShift
        merch_shift = _compute_merchant_affinity_shift(
            merch_lat, merch_lon, hist["merch_locations"]
        )

        # Dim 12: spendingVelocity
        spend_vel = _compute_spending_velocity(hist["amounts"], hist["timestamps"])

        # Also run the base model prediction for convenience
        df_input = pd.DataFrame([data])[REQUIRED_FIELDS]
        base_prediction = int(model.predict(df_input)[0])

        # Build response
        features = {
            "predictedNextTxnTime": round(predicted_next, 2),
            "burstProbability": round(burst_prob, 6),
            "periodicityScore": round(periodicity, 6),
            "timeOfDayRisk": round(tod_risk, 6),
            "isolationScore": round(isolation, 6),
            "clusterDeviation": round(cluster_dev, 6),
            "merchantAffinityShift": round(merch_shift, 6),
            "spendingVelocity": round(spend_vel, 6),
        }

        # Feature array in order (dims 5-12)
        feature_array = [
            features["predictedNextTxnTime"],
            features["burstProbability"],
            features["periodicityScore"],
            features["timeOfDayRisk"],
            features["isolationScore"],
            features["clusterDeviation"],
            features["merchantAffinityShift"],
            features["spendingVelocity"],
        ]

        return jsonify({
            "entityId": cc_num,
            "basePrediction": base_prediction,
            "extendedFeatures": features,
            "featureArray": feature_array,
            "dimensionRange": {"start": 5, "end": 12},
            "historyDepth": len(hist["amounts"]),
        })

    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/entity-history/<int:cc_num>", methods=["GET"])
def entity_history(cc_num):
    """
    Returns the in-memory history summary for an entity.
    Useful for debugging and Partner B's audit trail.
    """
    if cc_num not in _entity_history:
        return jsonify({"entityId": cc_num, "found": False}), 404

    hist = _entity_history[cc_num]
    return jsonify({
        "entityId": cc_num,
        "found": True,
        "transactionCount": len(hist["amounts"]),
        "totalSpend": round(sum(hist["amounts"]), 2),
        "avgAmount": round(sum(hist["amounts"]) / max(1, len(hist["amounts"])), 2),
        "intervalCount": len(hist["inter_txn_intervals"]),
        "avgInterval": round(
            sum(hist["inter_txn_intervals"]) / max(1, len(hist["inter_txn_intervals"])), 2
        ) if hist["inter_txn_intervals"] else None,
    })


@app.route("/health", methods=["GET"])
def health():
    """Health check endpoint."""
    return jsonify({
        "status": "healthy",
        "model_loaded": True,
        "tracked_entities": len(_entity_history),
        "endpoints": ["/predict", "/predict-extended", "/entity-history/<cc_num>", "/health"],
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", debug=True)