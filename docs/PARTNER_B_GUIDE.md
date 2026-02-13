# Partner B Developer Guide — Analytics Engine with Neo4j & Apache Spark

**Author:** For @Subho-aec (Partner B)
**Last Updated:** 2025

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Neo4j Setup Guide](#3-neo4j-setup-guide)
4. [Spark Setup Guide](#4-spark-setup-guide)
5. [Integration Points](#5-integration-points)
6. [Code Snippets](#6-code-snippets)
7. [What Each Component Does](#7-what-each-component-does)
8. [Getting Started from Ground Up](#8-getting-started-from-ground-up)
9. [Important Rules](#9-important-rules)

---

## 1. Overview

**Your role as Partner B** is to add an **analytics engine layer** to the existing fraud detection
system. You will introduce two new technologies:

- **Neo4j** — a graph database that stores transaction relationships persistently on disk
  (unlike the existing in-memory graph which resets when the app restarts).
- **Apache Spark** — a batch analytics engine for processing large volumes of transaction data
  and running distributed graph algorithms.

**What you are NOT doing:**

- You are NOT modifying any existing Java code, Python code, or frontend code.
- You are NOT replacing the existing in-memory graph engine — you are adding a persistent one
  alongside it.
- You are NOT changing how the current system works. You are adding new services that read from
  and complement the existing ones.

Think of it this way: the existing system handles **real-time** fraud detection. Your additions
handle **historical analysis** and **batch processing** — finding patterns across thousands of
transactions that happened over days or weeks.

---

## 2. Architecture

Here is how the full system looks after your additions:

```
┌──────────────────────────────────────────────────────────────────────┐
│                    EXISTING SERVICES (DO NOT TOUCH)                  │
│                                                                      │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────────┐   │
│  │   MariaDB     │    │  Quarkus Service  │    │  Python Flask ML  │   │
│  │  (port 3306)  │◄──►│   (port 8080)     │──►│   (port 5005)     │   │
│  │              │    │                  │    │                   │   │
│  │ transactions  │    │ /data-handler    │    │ /predict          │   │
│  │ fraud_logs    │    │ /graph/state     │    │ fraud model       │   │
│  └──────┬───────┘    │ /graph/metrics   │    └───────────────────┘   │
│         │            └────────┬─────────┘                            │
│         │                     │                                      │
│  ┌──────┴─────────────────────┴──────────────────────────────────┐   │
│  │                     fraud-network (Docker bridge)              │   │
│  └──────┬─────────────────────┬──────────────────────────────────┘   │
│         │                     │                                      │
│  ┌──────┴───────┐    ┌───────┴──────────┐                           │
│  │ Lit Frontend  │    │ React Visualizer  │                           │
│  │ (port 3000)   │    │  (port 3001)      │                           │
│  └──────────────┘    └──────────────────┘                           │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                    PARTNER B ADDITIONS (YOUR WORK)                    │
│                                                                      │
│  ┌──────────────────┐         ┌──────────────────────┐              │
│  │      Neo4j        │         │    Apache Spark       │              │
│  │  (port 7474 HTTP) │         │   (port 4040 UI)      │              │
│  │  (port 7687 Bolt) │         │                      │              │
│  │                  │         │  Reads from MariaDB   │              │
│  │  Persistent graph │         │  via JDBC             │              │
│  │  storage          │         │  Runs GraphX, batch   │              │
│  └────────┬─────────┘         │  analytics            │              │
│           │                   └──────────┬───────────┘              │
│           │                              │                          │
│  ┌────────┴──────────────────────────────┴───────────┐              │
│  │          Analytics Bridge Service                  │              │
│  │  (syncs data between MariaDB ──► Neo4j)            │              │
│  │  (pushes Spark results ──► Neo4j or MariaDB)       │              │
│  └───────────────────────────────────────────────────┘              │
└──────────────────────────────────────────────────────────────────────┘
```

**Data flows:**

1. Transactions come in through Quarkus and get stored in MariaDB.
2. Your Spark job reads transactions from MariaDB (via JDBC) in batches.
3. Your sync service (or Spark job) writes graph data into Neo4j.
4. You query Neo4j for historical fraud patterns, community detection, fraud rings.
5. Optionally, you push analytics results back into MariaDB or expose them via a new API.

---

## 3. Neo4j Setup Guide

### 3.1 What is Neo4j?

Neo4j is a **graph database**. Unlike MariaDB which stores data in tables (rows and columns),
Neo4j stores data as **nodes** (things) and **relationships** (connections between things).

For fraud detection, this is powerful because you can ask questions like:
- "Which accounts transacted with the same merchant?"
- "Are there clusters of accounts that keep sending money to each other?" (fraud rings)
- "What is the shortest path between two suspicious accounts?"

### 3.2 Adding Neo4j to compose.yaml

Add this block to the `services:` section of `compose.yaml` (at the bottom, before `networks:`):

```yaml
  neo4j:
    image: neo4j:5
    container_name: fraud-neo4j
    environment:
      NEO4J_AUTH: neo4j/fraud_neo4j_pass
      NEO4J_PLUGINS: '["graph-data-science"]'
      NEO4J_dbms_memory_heap_max__size: 512M
      NEO4J_dbms_memory_pagecache_size: 256M
    ports:
      - "7474:7474"   # HTTP — Neo4j Browser (web UI)
      - "7687:7687"   # Bolt — driver protocol (how code talks to Neo4j)
    volumes:
      - neo4j_data:/data
      - neo4j_logs:/logs
    networks:
      - fraud-network
    restart: always
```

Also add the volumes at the bottom of `compose.yaml` under the existing `volumes:` section:

```yaml
volumes:
  mariadb_data:       # already exists
  neo4j_data:         # add this
  neo4j_logs:         # add this
```

**What each setting does:**

| Setting | Meaning |
|---------|---------|
| `neo4j:5` | Uses Neo4j version 5 (latest stable) |
| `NEO4J_AUTH` | Sets the username (`neo4j`) and password (`fraud_neo4j_pass`) |
| `NEO4J_PLUGINS` | Installs the Graph Data Science plugin (for community detection, etc.) |
| Port `7474` | Opens the Neo4j Browser — a web UI where you can run queries |
| Port `7687` | Opens the Bolt protocol — this is how your Java/Python code connects |
| `fraud-network` | Puts Neo4j on the same Docker network as the other services |

### 3.3 Data Model

Your Neo4j graph will have the following structure based on the existing MariaDB
`transactions` table:

```
(:Account {cc_num: 123456789})
    -[:TRANSACTED_WITH {amount: 52.50, unix_time: 1371816893}]->
(:Merchant {merch_lat: 33.986, merch_long: -81.200})
```

**Nodes:**

| Label | Properties | Source Column |
|-------|-----------|---------------|
| `Account` | `cc_num` (unique identifier) | `transactions.cc_num` |
| `Merchant` | `merch_lat`, `merch_long` | `transactions.merch_lat`, `transactions.merch_long` |

**Relationships:**

| Type | Properties | Meaning |
|------|-----------|---------|
| `TRANSACTED_WITH` | `amount`, `unix_time`, `lat`, `long`, `zip`, `city_pop` | An account made a purchase at a merchant |

### 3.4 Initial Cypher Scripts

Cypher is Neo4j's query language (like SQL is for MariaDB). Here are the scripts to set up
your schema and import data.

**Step 1: Create uniqueness constraints** (run these in Neo4j Browser at http://localhost:7474)

```cypher
// Ensure each account appears only once
CREATE CONSTRAINT account_cc_num IF NOT EXISTS
FOR (a:Account) REQUIRE a.cc_num IS UNIQUE;

// Ensure each merchant location appears only once
CREATE CONSTRAINT merchant_location IF NOT EXISTS
FOR (m:Merchant) REQUIRE (m.merch_lat, m.merch_long) IS UNIQUE;
```

**Step 2: Create Account nodes from transactions**

```cypher
// This creates one Account node per unique credit card number.
// If the account already exists, it does nothing (MERGE = "find or create").
LOAD CSV WITH HEADERS FROM 'file:///transactions.csv' AS row
MERGE (a:Account {cc_num: toInteger(row.cc_num)});
```

Or, if you are inserting from your sync service (not CSV):

```cypher
// Create a single account node
MERGE (a:Account {cc_num: $ccNum})
RETURN a;
```

**Step 3: Create Merchant nodes**

```cypher
MERGE (m:Merchant {merch_lat: $merchLat, merch_long: $merchLong})
RETURN m;
```

**Step 4: Create TRANSACTED_WITH relationships**

```cypher
// Link an account to a merchant via a transaction
MATCH (a:Account {cc_num: $ccNum})
MATCH (m:Merchant {merch_lat: $merchLat, merch_long: $merchLong})
CREATE (a)-[:TRANSACTED_WITH {
  amount: $amount,
  unix_time: $unixTime,
  lat: $lat,
  long: $long,
  zip: $zip,
  city_pop: $cityPop
}]->(m)
```

### 3.5 Useful Cypher Queries for Fraud Analysis

**Find fraud rings (accounts connected through shared merchants):**

```cypher
// Find groups of accounts that share the same merchants.
// If 3+ accounts all transacted with the same merchant, that's suspicious.
MATCH (a1:Account)-[:TRANSACTED_WITH]->(m:Merchant)<-[:TRANSACTED_WITH]-(a2:Account)
WHERE a1 <> a2
WITH m, collect(DISTINCT a1) + collect(DISTINCT a2) AS accounts
WHERE size(accounts) >= 3
RETURN m.merch_lat, m.merch_long, 
       [a IN accounts | a.cc_num] AS connected_accounts,
       size(accounts) AS ring_size
ORDER BY ring_size DESC
LIMIT 20;
```

**Find shortest path between two accounts:**

```cypher
// How are two accounts connected? Through which merchants?
MATCH path = shortestPath(
  (a1:Account {cc_num: $ccNum1})-[:TRANSACTED_WITH*]-(a2:Account {cc_num: $ccNum2})
)
RETURN path;
```

**Community detection using Label Propagation (requires Graph Data Science plugin):**

```cypher
// First, create an in-memory graph projection
CALL gds.graph.project(
  'fraud-graph',
  ['Account', 'Merchant'],
  {TRANSACTED_WITH: {orientation: 'UNDIRECTED'}}
);

// Run label propagation — groups nodes into communities
CALL gds.labelPropagation.stream('fraud-graph')
YIELD nodeId, communityId
RETURN gds.util.asNode(nodeId).cc_num AS account,
       communityId
ORDER BY communityId, account;
```

**Find high-risk accounts (many transactions in short time):**

```cypher
MATCH (a:Account)-[t:TRANSACTED_WITH]->(m:Merchant)
WITH a, count(t) AS txn_count, sum(t.amount) AS total_amount,
     max(t.unix_time) - min(t.unix_time) AS time_span
WHERE txn_count > 10 AND time_span < 3600  // 10+ txns in under 1 hour
RETURN a.cc_num, txn_count, total_amount, time_span
ORDER BY txn_count DESC;
```

### 3.6 Connecting to Neo4j from Java

If you write a new Java service (a separate service, NOT modifying the existing Quarkus code),
you can use the official Neo4j Java driver.

**Maven dependency** (add to your new service's `pom.xml`):

```xml
<dependency>
    <groupId>org.neo4j.driver</groupId>
    <artifactId>neo4j-java-driver</artifactId>
    <version>5.13.0</version>
</dependency>
```

**Java code to connect and run queries:**

```java
import org.neo4j.driver.*;
import org.neo4j.driver.Record;

public class Neo4jClient implements AutoCloseable {

    private final Driver driver;

    public Neo4jClient() {
        // "neo4j" is the container name on the Docker network
        this.driver = GraphDatabase.driver(
            "bolt://neo4j:7687",
            AuthTokens.basic("neo4j", "fraud_neo4j_pass")
        );
    }

    // Create an account node
    public void createAccount(long ccNum) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(
                    "MERGE (a:Account {cc_num: $ccNum})",
                    Values.parameters("ccNum", ccNum)
                );
                return null;
            });
        }
    }

    // Create a transaction relationship
    public void createTransaction(long ccNum, double amount, long unixTime,
                                  float merchLat, float merchLong,
                                  float lat, float lon, String zip, int cityPop) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(
                    "MERGE (a:Account {cc_num: $ccNum}) " +
                    "MERGE (m:Merchant {merch_lat: $merchLat, merch_long: $merchLong}) " +
                    "CREATE (a)-[:TRANSACTED_WITH {" +
                    "  amount: $amount, unix_time: $unixTime," +
                    "  lat: $lat, long: $lon, zip: $zip, city_pop: $cityPop" +
                    "}]->(m)",
                    Values.parameters(
                        "ccNum", ccNum,
                        "amount", amount,
                        "unixTime", unixTime,
                        "merchLat", merchLat,
                        "merchLong", merchLong,
                        "lat", lat,
                        "lon", lon,
                        "zip", zip,
                        "cityPop", cityPop
                    )
                );
                return null;
            });
        }
    }

    // Find fraud rings
    public void findFraudRings() {
        try (Session session = driver.session()) {
            Result result = session.run(
                "MATCH (a1:Account)-[:TRANSACTED_WITH]->(m:Merchant)" +
                "<-[:TRANSACTED_WITH]-(a2:Account) " +
                "WHERE a1 <> a2 " +
                "WITH m, collect(DISTINCT a1.cc_num) AS accounts " +
                "WHERE size(accounts) >= 3 " +
                "RETURN accounts, size(accounts) AS ring_size " +
                "ORDER BY ring_size DESC LIMIT 10"
            );

            while (result.hasNext()) {
                Record record = result.next();
                System.out.println("Fraud ring of size " +
                    record.get("ring_size").asInt() + ": " +
                    record.get("accounts").asList());
            }
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
```

**Usage:**

```java
public static void main(String[] args) {
    try (Neo4jClient client = new Neo4jClient()) {
        // Insert a transaction
        client.createAccount(4263982290012345L);
        client.createTransaction(
            4263982290012345L,  // cc_num
            52.50,             // amount
            1371816893L,       // unix_time
            33.986f,           // merch_lat
            -81.200f,          // merch_long
            34.500f,           // lat
            -80.100f,          // lon
            "29201",           // zip
            45000              // city_pop
        );

        // Find fraud rings
        client.findFraudRings();
    }
}
```

---

## 4. Spark Setup Guide

### 4.1 What is Apache Spark?

Apache Spark is an engine for processing large datasets. Think of it as:
- MariaDB can handle thousands of transactions → Spark can handle **millions**.
- Instead of querying row by row, Spark processes everything in parallel.
- Spark's **GraphX** library can run graph algorithms (PageRank, connected components)
  on massive datasets that would be too large for Neo4j's in-memory processing.

### 4.2 Adding Spark to compose.yaml

Add these blocks to the `services:` section of `compose.yaml`:

```yaml
  spark-master:
    image: bitnami/spark:3
    container_name: fraud-spark-master
    environment:
      SPARK_MODE: master
      SPARK_MASTER_HOST: spark-master
    ports:
      - "4040:8080"  # Spark Web UI (mapped to 4040 to avoid conflict with Quarkus)
      - "7077:7077"  # Spark master port
    networks:
      - fraud-network
    restart: always

  spark-worker:
    image: bitnami/spark:3
    container_name: fraud-spark-worker
    environment:
      SPARK_MODE: worker
      SPARK_MASTER_URL: spark://spark-master:7077
      SPARK_WORKER_MEMORY: 1G
      SPARK_WORKER_CORES: 2
    depends_on:
      - spark-master
    networks:
      - fraud-network
    restart: always
```

**What each setting does:**

| Setting | Meaning |
|---------|---------|
| `bitnami/spark:3` | Uses Apache Spark 3.x from Bitnami (pre-configured Docker image) |
| `SPARK_MODE: master` | This container coordinates the work |
| `SPARK_MODE: worker` | This container does the actual computation |
| Port `4040` | Spark Web UI — shows running jobs, stages, and performance |
| Port `7077` | Internal port for master-worker communication |

### 4.3 Reading from MariaDB via JDBC (PySpark)

PySpark (Python + Spark) is the easiest way to get started. Here is a complete script that
reads transactions from MariaDB and processes them.

**File: `analytics/spark_jobs/read_transactions.py`** (create this file in your new directory)

```python
from pyspark.sql import SparkSession

# Create a Spark session
spark = SparkSession.builder \
    .appName("FraudAnalytics") \
    .master("spark://spark-master:7077") \
    .config("spark.jars", "/opt/spark/jars/mariadb-java-client-3.1.4.jar") \
    .getOrCreate()

# Read the transactions table from MariaDB
# "fraud-db" is the MariaDB container name on the Docker network
transactions_df = spark.read \
    .format("jdbc") \
    .option("url", "jdbc:mariadb://fraud-db:3306/fraud_detection") \
    .option("dbtable", "transactions") \
    .option("user", "fraud_user") \
    .option("password", "fraud_pass") \
    .option("driver", "org.mariadb.jdbc.Driver") \
    .load()

# Show the first 10 rows
transactions_df.show(10)

# Print the schema (column names and types)
transactions_df.printSchema()

# Basic statistics
print(f"Total transactions: {transactions_df.count()}")
print(f"Unique accounts: {transactions_df.select('cc_num').distinct().count()}")

# Find accounts with the most transactions
transactions_df.groupBy("cc_num") \
    .count() \
    .orderBy("count", ascending=False) \
    .show(10)

# Find high-value transactions (potential fraud)
high_value = transactions_df.filter(transactions_df.amt > 500)
print(f"High-value transactions (>$500): {high_value.count()}")
high_value.show(10)

spark.stop()
```

**To run this script:**

```bash
# First, download the MariaDB JDBC driver into the Spark container
docker exec fraud-spark-master bash -c \
  "curl -L -o /opt/bitnami/spark/jars/mariadb-java-client-3.1.4.jar \
   https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.1.4/mariadb-java-client-3.1.4.jar"

# Copy your script into the container
docker cp analytics/spark_jobs/read_transactions.py fraud-spark-master:/tmp/

# Run it
docker exec fraud-spark-master \
  spark-submit --master spark://spark-master:7077 \
  /tmp/read_transactions.py
```

### 4.4 Graph Analytics with GraphX (Scala/PySpark)

Spark GraphX lets you run graph algorithms on large datasets. Here is an example
using **GraphFrames** (the DataFrame-based API which works with PySpark):

**File: `analytics/spark_jobs/graph_analytics.py`**

```python
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from graphframes import GraphFrame

spark = SparkSession.builder \
    .appName("FraudGraphAnalytics") \
    .master("spark://spark-master:7077") \
    .config("spark.jars.packages", "graphframes:graphframes:0.8.3-spark3.5-s_2.12") \
    .config("spark.jars", "/opt/bitnami/spark/jars/mariadb-java-client-3.1.4.jar") \
    .getOrCreate()

# Read transactions from MariaDB
transactions_df = spark.read \
    .format("jdbc") \
    .option("url", "jdbc:mariadb://fraud-db:3306/fraud_detection") \
    .option("dbtable", "transactions") \
    .option("user", "fraud_user") \
    .option("password", "fraud_pass") \
    .option("driver", "org.mariadb.jdbc.Driver") \
    .load()

# -----------------------------------------------------------
# Build a graph: accounts are vertices, shared merchants = edges
# -----------------------------------------------------------

# Create vertex DataFrame (each unique account is a vertex)
vertices = transactions_df \
    .select(F.col("cc_num").cast("string").alias("id")) \
    .distinct()

# Create edge DataFrame (two accounts share an edge if they
# transacted with the same merchant location)
edges = transactions_df.alias("t1") \
    .join(
        transactions_df.alias("t2"),
        (F.col("t1.merch_lat") == F.col("t2.merch_lat")) &
        (F.col("t1.merch_long") == F.col("t2.merch_long")) &
        (F.col("t1.cc_num") != F.col("t2.cc_num"))
    ) \
    .select(
        F.col("t1.cc_num").cast("string").alias("src"),
        F.col("t2.cc_num").cast("string").alias("dst")
    ) \
    .distinct()

# Build the graph
graph = GraphFrame(vertices, edges)

# -----------------------------------------------------------
# PageRank — find the most "central" accounts
# -----------------------------------------------------------
# Accounts with high PageRank are connected to many other accounts
# through shared merchants. Could be legitimate hubs or fraud coordinators.

print("=== PageRank Results ===")
pagerank = graph.pageRank(resetProbability=0.15, maxIter=10)
pagerank.vertices \
    .select("id", "pagerank") \
    .orderBy("pagerank", ascending=False) \
    .show(20)

# -----------------------------------------------------------
# Connected Components — find clusters of related accounts
# -----------------------------------------------------------
# Each cluster gets a unique component ID. Large clusters might be fraud rings.

print("=== Connected Components ===")
spark.sparkContext.setCheckpointDir("/tmp/graphframes-checkpoints")
components = graph.connectedComponents()
components \
    .groupBy("component") \
    .count() \
    .orderBy("count", ascending=False) \
    .show(20)

# -----------------------------------------------------------
# Triangle Count — find tightly connected groups
# -----------------------------------------------------------
# A triangle means 3 accounts all share merchants with each other.
# High triangle counts suggest organized fraud.

print("=== Triangle Counts ===")
triangles = graph.triangleCount()
triangles \
    .select("id", "count") \
    .orderBy("count", ascending=False) \
    .show(20)

spark.stop()
```

### 4.5 Batch Fraud Pattern Detection (PySpark)

**File: `analytics/spark_jobs/fraud_patterns.py`**

```python
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.window import Window

spark = SparkSession.builder \
    .appName("FraudPatternDetection") \
    .master("spark://spark-master:7077") \
    .config("spark.jars", "/opt/bitnami/spark/jars/mariadb-java-client-3.1.4.jar") \
    .getOrCreate()

transactions_df = spark.read \
    .format("jdbc") \
    .option("url", "jdbc:mariadb://fraud-db:3306/fraud_detection") \
    .option("dbtable", "transactions") \
    .option("user", "fraud_user") \
    .option("password", "fraud_pass") \
    .option("driver", "org.mariadb.jdbc.Driver") \
    .load()

# -----------------------------------------------------------
# Pattern 1: Velocity check — too many transactions too fast
# -----------------------------------------------------------
window_spec = Window.partitionBy("cc_num").orderBy("unix_time")

velocity_df = transactions_df \
    .withColumn("prev_time", F.lag("unix_time").over(window_spec)) \
    .withColumn("time_gap", F.col("unix_time") - F.col("prev_time")) \
    .filter(F.col("time_gap") < 60)  # Less than 60 seconds apart

print("=== Rapid-fire Transactions (under 60s apart) ===")
velocity_df.select("cc_num", "amt", "unix_time", "time_gap").show(20)

# -----------------------------------------------------------
# Pattern 2: Geographic impossibility — large distance in short time
# -----------------------------------------------------------
geo_df = transactions_df \
    .withColumn("prev_lat", F.lag("lat").over(window_spec)) \
    .withColumn("prev_long", F.lag("long").over(window_spec)) \
    .withColumn("prev_time", F.lag("unix_time").over(window_spec)) \
    .filter(F.col("prev_lat").isNotNull()) \
    .withColumn("distance", F.sqrt(
        F.pow(F.col("lat") - F.col("prev_lat"), 2) +
        F.pow(F.col("long") - F.col("prev_long"), 2)
    )) \
    .withColumn("time_gap", F.col("unix_time") - F.col("prev_time")) \
    .filter((F.col("distance") > 1.0) & (F.col("time_gap") < 3600))

print("=== Geographic Impossibility (>1 degree, <1 hour) ===")
geo_df.select("cc_num", "distance", "time_gap", "amt").show(20)

# -----------------------------------------------------------
# Pattern 3: Unusual amounts — statistical outliers per account
# -----------------------------------------------------------
stats_df = transactions_df.groupBy("cc_num").agg(
    F.avg("amt").alias("avg_amt"),
    F.stddev("amt").alias("stddev_amt")
)

outliers_df = transactions_df.join(stats_df, "cc_num") \
    .filter(F.col("amt") > F.col("avg_amt") + 3 * F.col("stddev_amt")) \
    .select("cc_num", "amt", "avg_amt", "stddev_amt", "unix_time")

print("=== Statistical Outliers (>3 std deviations) ===")
outliers_df.show(20)

spark.stop()
```

---

## 5. Integration Points

### 5.1 How Your Services Connect to the Existing System

```
                ┌─────────────┐
                │   MariaDB    │
                │ (fraud-db)   │
                │ port 3306    │
                └──────┬──────┘
                       │
          ┌────────────┼────────────┐
          │ READ (JDBC) │            │ READ (JDBC)
          ▼            ▼            ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │  Quarkus  │ │  Neo4j    │ │  Spark   │
    │ (exists)  │ │ (yours)   │ │ (yours)  │
    └──────────┘ └──────────┘ └──────────┘
```

### 5.2 Reading the Quarkus Graph State

The existing Quarkus service exposes graph data at `GET /graph/state`. You can call this
from your services to get the real-time in-memory graph state.

```bash
# From inside any container on the fraud-network:
curl http://quarkus-service:8080/graph/state

# From your host machine:
curl http://localhost:8080/graph/state
```

This returns JSON with the current truth graph and hypothesis graph state. You can use
this to compare your Neo4j historical analysis with the real-time view.

### 5.3 Reading from MariaDB Directly

Your services can read the `transactions` and `fraud_logs` tables directly via JDBC.

**Connection details (from inside Docker network):**

| Property | Value |
|----------|-------|
| Host | `fraud-db` (container name) |
| Port | `3306` |
| Database | `fraud_detection` |
| Username | `fraud_user` |
| Password | `fraud_pass` |
| JDBC URL | `jdbc:mariadb://fraud-db:3306/fraud_detection` |

**Tables you can read:**

```sql
-- transactions table
SELECT * FROM transactions;
-- Columns: id, cc_num, amt, zip, lat, long, city_pop, unix_time, merch_lat, merch_long

-- fraud_logs table
SELECT * FROM fraud_logs;
-- Columns: id, cc_num, reason, detected_time, transaction_data (JSON)
```

> **Important:** Treat MariaDB as **read-only**. Do not insert, update, or delete rows
> in the existing tables. If you need to store analytics results, create a new table
> (e.g., `neo4j_analytics_results`) or store them in Neo4j.

### 5.4 How Neo4j Complements the Existing In-Memory Graph

| Feature | Existing (Quarkus) | Your Addition (Neo4j) |
|---------|-------------------|-----------------------|
| Storage | In-memory (lost on restart) | Persistent on disk |
| Speed | Very fast (nanoseconds) | Fast (milliseconds) |
| Scale | Limited by JVM heap | Limited by disk space |
| Query language | Java API calls | Cypher queries |
| Best for | Real-time detection | Historical analysis |
| Graph algorithms | Custom (GraphMathEngine) | GDS library (optimized) |
| Survives restart? | No | Yes |

### 5.5 Pushing Results Back

If your analysis finds something interesting (e.g., a fraud ring), you have options:

**Option A: Insert into a new MariaDB table**

```sql
CREATE TABLE IF NOT EXISTS analytics_results (
    id INT AUTO_INCREMENT PRIMARY KEY,
    analysis_type VARCHAR(50) NOT NULL,
    cc_num BIGINT,
    result_data JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**Option B: Store in Neo4j (add properties to nodes)**

```cypher
// Mark an account as part of a fraud ring
MATCH (a:Account {cc_num: $ccNum})
SET a.fraud_ring_id = $ringId,
    a.risk_score = $riskScore,
    a.analyzed_at = datetime()
```

**Option C: Call the Quarkus API** (if Partner A exposes a suitable endpoint)

```bash
curl -X POST http://quarkus-service:8080/data-handler \
  -H "Content-Type: application/json" \
  -d '{"type": "analytics_result", "data": {...}}'
```

---

## 6. Code Snippets Reference

### 6.1 Complete compose.yaml Additions

Here is exactly what to add to the existing `compose.yaml`. Do NOT delete or change anything
that is already there. Add these services and volumes:

```yaml
# =========================================================
# ADD THESE SERVICES (paste before the "networks:" section)
# =========================================================

  neo4j:
    image: neo4j:5
    container_name: fraud-neo4j
    environment:
      NEO4J_AUTH: neo4j/fraud_neo4j_pass
      NEO4J_PLUGINS: '["graph-data-science"]'
      NEO4J_dbms_memory_heap_max__size: 512M
      NEO4J_dbms_memory_pagecache_size: 256M
    ports:
      - "7474:7474"
      - "7687:7687"
    volumes:
      - neo4j_data:/data
      - neo4j_logs:/logs
    networks:
      - fraud-network
    restart: always

  spark-master:
    image: bitnami/spark:3
    container_name: fraud-spark-master
    environment:
      SPARK_MODE: master
      SPARK_MASTER_HOST: spark-master
    ports:
      - "4040:8080"
      - "7077:7077"
    networks:
      - fraud-network
    restart: always

  spark-worker:
    image: bitnami/spark:3
    container_name: fraud-spark-worker
    environment:
      SPARK_MODE: worker
      SPARK_MASTER_URL: spark://spark-master:7077
      SPARK_WORKER_MEMORY: 1G
      SPARK_WORKER_CORES: 2
    depends_on:
      - spark-master
    networks:
      - fraud-network
    restart: always

# =========================================================
# UPDATE the volumes section to include Neo4j volumes:
# =========================================================
# volumes:
#   mariadb_data:     <-- already exists, keep it
#   neo4j_data:       <-- add this line
#   neo4j_logs:       <-- add this line
```

### 6.2 Java Neo4j Driver — Full Sync Service Example

This is a standalone Java application that reads from MariaDB and writes to Neo4j.
Create this as a **new project** — do NOT put it in the existing Quarkus service.

```java
import org.neo4j.driver.*;
import java.sql.*;

public class MariaDbToNeo4jSync implements AutoCloseable {

    private final Driver neo4jDriver;
    private final Connection mariaDbConn;

    public MariaDbToNeo4jSync() throws SQLException {
        // Connect to Neo4j
        this.neo4jDriver = GraphDatabase.driver(
            "bolt://neo4j:7687",
            AuthTokens.basic("neo4j", "fraud_neo4j_pass")
        );

        // Connect to MariaDB
        this.mariaDbConn = DriverManager.getConnection(
            "jdbc:mariadb://fraud-db:3306/fraud_detection",
            "fraud_user",
            "fraud_pass"
        );
    }

    public void syncAllTransactions() throws SQLException {
        String sql = "SELECT cc_num, amt, zip, lat, `long`, city_pop, " +
                     "unix_time, merch_lat, merch_long FROM transactions";

        try (Statement stmt = mariaDbConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql);
             Session neo4jSession = neo4jDriver.session()) {

            int count = 0;
            while (rs.next()) {
                long ccNum = rs.getLong("cc_num");
                double amt = rs.getDouble("amt");
                String zip = rs.getString("zip");
                float lat = rs.getFloat("lat");
                float lon = rs.getFloat("long");
                int cityPop = rs.getInt("city_pop");
                long unixTime = rs.getLong("unix_time");
                float merchLat = rs.getFloat("merch_lat");
                float merchLong = rs.getFloat("merch_long");

                neo4jSession.executeWrite(tx -> {
                    tx.run(
                        "MERGE (a:Account {cc_num: $ccNum}) " +
                        "MERGE (m:Merchant {merch_lat: $merchLat, merch_long: $merchLong}) " +
                        "CREATE (a)-[:TRANSACTED_WITH {" +
                        "  amount: $amt, unix_time: $unixTime," +
                        "  lat: $lat, long: $lon, zip: $zip, city_pop: $cityPop" +
                        "}]->(m)",
                        Values.parameters(
                            "ccNum", ccNum, "amt", amt, "unixTime", unixTime,
                            "merchLat", merchLat, "merchLong", merchLong,
                            "lat", lat, "lon", lon, "zip", zip, "cityPop", cityPop
                        )
                    );
                    return null;
                });

                count++;
                if (count % 100 == 0) {
                    System.out.println("Synced " + count + " transactions...");
                }
            }
            System.out.println("Done! Synced " + count + " total transactions.");
        }
    }

    @Override
    public void close() throws Exception {
        neo4jDriver.close();
        mariaDbConn.close();
    }

    public static void main(String[] args) throws Exception {
        try (MariaDbToNeo4jSync sync = new MariaDbToNeo4jSync()) {
            sync.syncAllTransactions();
        }
    }
}
```

### 6.3 Python PySpark — Read from MariaDB

```python
from pyspark.sql import SparkSession

spark = SparkSession.builder \
    .appName("ReadMariaDB") \
    .master("spark://spark-master:7077") \
    .config("spark.jars", "/opt/bitnami/spark/jars/mariadb-java-client-3.1.4.jar") \
    .getOrCreate()

# Read the entire transactions table
df = spark.read \
    .format("jdbc") \
    .option("url", "jdbc:mariadb://fraud-db:3306/fraud_detection") \
    .option("dbtable", "transactions") \
    .option("user", "fraud_user") \
    .option("password", "fraud_pass") \
    .option("driver", "org.mariadb.jdbc.Driver") \
    .load()

# Show what we got
df.show(5)
# +---+-----------+-----+-----+--------+--------+----------+----------+---------+-----------+
# | id|     cc_num|  amt|  zip|     lat|    long|  city_pop| unix_time| merch_lat| merch_long|
# +---+-----------+-----+-----+--------+--------+----------+----------+---------+-----------+

# Count by account
df.groupBy("cc_num").count().orderBy("count", ascending=False).show(10)

spark.stop()
```

### 6.4 Cypher Fraud Ring Detection (Copy-Paste Ready)

```cypher
// =====================================================
// STEP 1: Find accounts that share merchants
// =====================================================
MATCH (a1:Account)-[:TRANSACTED_WITH]->(m:Merchant)<-[:TRANSACTED_WITH]-(a2:Account)
WHERE a1.cc_num < a2.cc_num   // avoid duplicates
RETURN a1.cc_num AS account_1,
       a2.cc_num AS account_2,
       m.merch_lat AS merchant_lat,
       m.merch_long AS merchant_long
LIMIT 50;

// =====================================================
// STEP 2: Find clusters of 3+ accounts sharing merchants
// =====================================================
MATCH (a:Account)-[:TRANSACTED_WITH]->(m:Merchant)
WITH m, collect(DISTINCT a.cc_num) AS accounts
WHERE size(accounts) >= 3
RETURN m.merch_lat AS merchant_lat,
       m.merch_long AS merchant_long,
       accounts,
       size(accounts) AS cluster_size
ORDER BY cluster_size DESC;

// =====================================================
// STEP 3: Full fraud ring detection with GDS
// =====================================================

// Create graph projection
CALL gds.graph.project(
  'fraud-ring-graph',
  'Account',
  {
    SHARED_MERCHANT: {
      type: 'TRANSACTED_WITH',
      orientation: 'UNDIRECTED'
    }
  }
);

// Run weakly connected components
CALL gds.wcc.stream('fraud-ring-graph')
YIELD nodeId, componentId
WITH componentId, collect(gds.util.asNode(nodeId).cc_num) AS members
WHERE size(members) >= 3
RETURN componentId AS ring_id,
       members,
       size(members) AS ring_size
ORDER BY ring_size DESC;

// Clean up projection when done
CALL gds.graph.drop('fraud-ring-graph');
```

### 6.5 Spark GraphX PageRank (Scala)

If you prefer Scala (Spark's native language), here is a GraphX PageRank example:

```scala
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

val spark = SparkSession.builder
  .appName("FraudPageRank")
  .master("spark://spark-master:7077")
  .getOrCreate()

val sc = spark.sparkContext

// Read transactions from MariaDB
val txnDF = spark.read
  .format("jdbc")
  .option("url", "jdbc:mariadb://fraud-db:3306/fraud_detection")
  .option("dbtable", "transactions")
  .option("user", "fraud_user")
  .option("password", "fraud_pass")
  .option("driver", "org.mariadb.jdbc.Driver")
  .load()

// Build vertices: each unique cc_num gets a unique Long ID
val accounts = txnDF.select("cc_num").distinct().rdd
  .map(row => row.getLong(0))
  .zipWithIndex()
  .map { case (ccNum, idx) => (idx, ccNum) }

val accountToIdx = accounts.map { case (idx, ccNum) => (ccNum, idx) }
  .collectAsMap()

// Build edges: accounts sharing a merchant are connected
val edges: RDD[Edge[Double]] = txnDF.rdd.map { row =>
  val ccNum = row.getAs[Long]("cc_num")
  val merchKey = s"${row.getAs[Float]("merch_lat")}_${row.getAs[Float]("merch_long")}"
  (merchKey, ccNum)
}.groupByKey().flatMap { case (_, accounts) =>
  val accountList = accounts.toList.distinct
  for {
    a1 <- accountList
    a2 <- accountList if a1 < a2
  } yield Edge(accountToIdx(a1), accountToIdx(a2), 1.0)
}

// Build the graph
val graph = Graph(accounts, edges)

// Run PageRank (10 iterations)
val ranks = graph.pageRank(0.001).vertices

// Print top 20 accounts by PageRank
val topAccounts = ranks.join(accounts.map(_.swap).map(_.swap))
  .map { case (_, (rank, ccNum)) => (ccNum, rank) }
  .sortBy(_._2, ascending = false)
  .take(20)

println("=== Top 20 Accounts by PageRank ===")
topAccounts.foreach { case (ccNum, rank) =>
  println(f"Account $ccNum: PageRank = $rank%.6f")
}

spark.stop()
```

---

## 7. What Each Component Does

### Summary Table

| Component | Owner | Purpose | Port(s) | Data |
|-----------|-------|---------|---------|------|
| **MariaDB** | Existing | Source of truth for all transactions and fraud logs | 3306 | `transactions`, `fraud_logs` tables |
| **Quarkus** | Partner A (existing) | Real-time fraud detection, in-memory graph, REST API | 8080 | `/data-handler`, `/graph/state`, `/graph/metrics` |
| **Python Flask** | Existing | ML model for fraud prediction | 5005 | `/predict` endpoint |
| **Lit Frontend** | Existing | User interface for submitting transactions | 3000 | Web UI |
| **React Visualizer** | Existing | Real-time graph visualization | 3001 | Web UI |
| **Neo4j** | **You (Partner B)** | Persistent graph database for historical analysis | 7474, 7687 | Account/Merchant nodes, TRANSACTED_WITH relationships |
| **Apache Spark** | **You (Partner B)** | Batch analytics, distributed graph algorithms | 4040, 7077 | Reads from MariaDB, outputs to Neo4j or new tables |

### Detailed Descriptions

**Neo4j — Persistent Graph Store**
- Stores the entire transaction history as a graph (accounts → merchants).
- Supports Cypher queries for pattern matching ("find all accounts linked to this merchant").
- The Graph Data Science library provides optimized algorithms:
  - **Community Detection** — groups accounts into clusters (Label Propagation, Louvain).
  - **Centrality** — finds the most connected/important accounts (PageRank, Betweenness).
  - **Path Finding** — finds how accounts are connected (Shortest Path, A*).
  - **Similarity** — finds accounts with similar transaction patterns.
- Data survives container restarts (stored in a Docker volume).

**Apache Spark — Batch Processing Engine**
- Processes the full transaction dataset in parallel (can handle millions of rows).
- **GraphX** runs distributed graph algorithms (PageRank, Connected Components, Triangle Count).
- Good for overnight batch jobs: "analyze all transactions from the past week."
- Reads directly from MariaDB via JDBC — no need to export data.
- Can write results to Neo4j, MariaDB, or CSV files.

**MariaDB — Source of Truth (Read-Only for You)**
- Contains the `transactions` table with all raw transaction data.
- Contains the `fraud_logs` table with flagged fraud cases.
- You can read from it, but do not write to the existing tables.
- You may create new tables for analytics results.

**Quarkus Graph Endpoints — Real-Time State**
- `GET /graph/state` — returns the current in-memory graph (nodes, edges, weights).
- `GET /graph/metrics` — returns analytics like graph density, PageRank, risk scores.
- `GET /graph/entity/{id}` — returns state for a specific entity.
- This is the real-time view; your Neo4j is the historical view.

---

## 8. Getting Started from Ground Up

Follow these steps in order. Each step builds on the previous one.

### Step 1: Install Docker and Docker Compose

If you don't have Docker installed:

```bash
# On Ubuntu/Debian
sudo apt-get update
sudo apt-get install docker.io docker-compose-v2 -y
sudo usermod -aG docker $USER
# Log out and back in for group changes to take effect

# On macOS — download Docker Desktop from https://www.docker.com/products/docker-desktop/
# On Windows — download Docker Desktop and enable WSL2 backend
```

Verify installation:

```bash
docker --version          # Should show Docker version 20+
docker compose version    # Should show Docker Compose v2+
```

### Step 2: Clone the Repository

```bash
git clone <your-repo-url>
cd fraudmonorepo
```

### Step 3: Start the Existing Services

```bash
# This builds and starts ALL existing services
docker compose up --build -d

# Wait about 30 seconds for everything to start, then check status
docker compose ps
```

You should see all 5 containers running:

```
NAME                    STATUS
fraud-db                Up (healthy)
quarkus-service         Up
python-quarkus-service  Up
fraud-frontend          Up
graph-visualizer        Up
```

Verify the services are accessible:
- MariaDB: `docker exec fraud-db mariadb -ufraud_user -pfraud_pass -e "SHOW TABLES" fraud_detection`
- Quarkus: `curl http://localhost:8080/graph/state`
- Frontend: Open http://localhost:3000 in your browser
- Visualizer: Open http://localhost:3001 in your browser

### Step 4: Add Neo4j to compose.yaml

Open `compose.yaml` in your editor and add the Neo4j service block (see Section 6.1 for
the exact YAML to paste). Then update the `volumes:` section to include `neo4j_data` and
`neo4j_logs`.

### Step 5: Start Neo4j

```bash
# Start only the Neo4j service (existing services keep running)
docker compose up neo4j -d

# Check it's running
docker compose ps neo4j

# Wait about 15 seconds for Neo4j to initialize
docker compose logs neo4j --tail 20
```

### Step 6: Access Neo4j Browser

Open your web browser and go to: **http://localhost:7474**

You will see the Neo4j Browser login screen:
- **Connect URL:** `bolt://localhost:7687` (should be pre-filled)
- **Username:** `neo4j`
- **Password:** `fraud_neo4j_pass`

Click **Connect**. You should see the Neo4j Browser interface where you can type Cypher queries.

### Step 7: Create the Schema

In the Neo4j Browser query box at the top, paste and run these commands one at a time:

```cypher
CREATE CONSTRAINT account_cc_num IF NOT EXISTS
FOR (a:Account) REQUIRE a.cc_num IS UNIQUE;
```

```cypher
CREATE CONSTRAINT merchant_location IF NOT EXISTS
FOR (m:Merchant) REQUIRE (m.merch_lat, m.merch_long) IS UNIQUE;
```

### Step 8: Populate Neo4j from MariaDB

You have two options:

**Option A: Quick manual test** — Insert a few records by hand in Neo4j Browser:

```cypher
CREATE (a:Account {cc_num: 4263982290012345})
CREATE (m:Merchant {merch_lat: 33.986, merch_long: -81.200})
CREATE (a)-[:TRANSACTED_WITH {amount: 52.50, unix_time: 1371816893}]->(m)
```

**Option B: Write a sync service** — Use the Java sync service from Section 6.2, or write
a PySpark job (Section 4.3) that reads from MariaDB and writes to Neo4j.

**Option C: Use a simple Python script** (easiest for beginners):

```python
# File: analytics/sync_to_neo4j.py
# Install: pip install neo4j mysql-connector-python

from neo4j import GraphDatabase
import mysql.connector

# Connect to MariaDB
mariadb = mysql.connector.connect(
    host="localhost",        # use "fraud-db" if running inside Docker
    port=3306,
    user="fraud_user",
    password="fraud_pass",
    database="fraud_detection"
)

# Connect to Neo4j
neo4j_driver = GraphDatabase.driver(
    "bolt://localhost:7687",  # use "bolt://neo4j:7687" if running inside Docker
    auth=("neo4j", "fraud_neo4j_pass")
)

# Read transactions from MariaDB
cursor = mariadb.cursor(dictionary=True)
cursor.execute("SELECT * FROM transactions")
rows = cursor.fetchall()

print(f"Found {len(rows)} transactions to sync")

# Write to Neo4j
with neo4j_driver.session() as session:
    for i, row in enumerate(rows):
        session.run(
            "MERGE (a:Account {cc_num: $ccNum}) "
            "MERGE (m:Merchant {merch_lat: $merchLat, merch_long: $merchLong}) "
            "CREATE (a)-[:TRANSACTED_WITH {"
            "  amount: $amt, unix_time: $unixTime,"
            "  lat: $lat, long: $lon, zip: $zip, city_pop: $cityPop"
            "}]->(m)",
            ccNum=row["cc_num"],
            amt=float(row["amt"]),
            unixTime=row["unix_time"],
            merchLat=row["merch_lat"],
            merchLong=row["merch_long"],
            lat=row["lat"],
            lon=row["long"],
            zip=row["zip"],
            cityPop=row["city_pop"]
        )
        if (i + 1) % 100 == 0:
            print(f"  Synced {i + 1}/{len(rows)} transactions...")

print("Sync complete!")

# Clean up
cursor.close()
mariadb.close()
neo4j_driver.close()
```

Run it:

```bash
pip install neo4j mysql-connector-python
python analytics/sync_to_neo4j.py
```

### Step 9: Verify the Data in Neo4j

Go back to the Neo4j Browser (http://localhost:7474) and run:

```cypher
// Count nodes and relationships
MATCH (a:Account) RETURN count(a) AS accounts;
MATCH (m:Merchant) RETURN count(m) AS merchants;
MATCH ()-[t:TRANSACTED_WITH]->() RETURN count(t) AS transactions;
```

### Step 10: Run Your First Analysis

Try the fraud ring detection query from Section 3.5 in the Neo4j Browser!

---

## 9. Important Rules

### ⛔ DO NOT Modify These (Existing Code)

| What | Location | Why |
|------|----------|-----|
| Java files | `backendservices/quarkus-service/src/main/java/org/frauddetection/**` | Partner A's code — real-time fraud engine |
| Python files | `backendservices/python-quarkus-service/**` | ML prediction model |
| Lit frontend | `frontendservices/**` | Transaction submission UI |
| React visualizer | `graph-visualizer/**` | Graph visualization UI |
| Database schema | `init-db.sql` (existing tables) | MariaDB init script |
| Existing compose services | `compose.yaml` (existing service blocks) | Running services |

### ✅ DO These (Your Additions)

| Action | Details |
|--------|---------|
| **Add** new services to `compose.yaml` | Neo4j, Spark master, Spark worker |
| **Add** new volumes to `compose.yaml` | `neo4j_data`, `neo4j_logs` |
| **Create** new directories for your code | e.g., `analytics/`, `analytics/spark_jobs/` |
| **Create** new Java/Python projects | Sync services, Spark jobs, analytics scripts |
| **Create** new MariaDB tables | For storing analytics results (don't change existing tables) |
| **Read** from existing MariaDB tables | `transactions` and `fraud_logs` are read-only for you |
| **Read** from Quarkus REST API | `GET /graph/state`, `GET /graph/metrics` |

### 🐳 Docker Network

All services communicate over the `fraud-network` Docker bridge network. When your code
runs inside a Docker container on this network, use **container names** as hostnames:

| Service | Hostname (inside Docker) | Hostname (from your laptop) |
|---------|-------------------------|-----------------------------|
| MariaDB | `fraud-db` | `localhost` |
| Quarkus | `quarkus-service` | `localhost` |
| Neo4j | `neo4j` (or `fraud-neo4j`) | `localhost` |
| Spark Master | `spark-master` | `localhost` |

### 📁 Suggested Directory Structure for Your Code

```
fraudmonorepo/
├── analytics/                    # YOUR NEW DIRECTORY
│   ├── sync_to_neo4j.py          # Python sync script
│   ├── spark_jobs/
│   │   ├── read_transactions.py  # PySpark: read from MariaDB
│   │   ├── graph_analytics.py    # PySpark: GraphX algorithms
│   │   └── fraud_patterns.py     # PySpark: batch pattern detection
│   └── cypher/
│       ├── schema.cypher         # Neo4j constraints and indexes
│       └── fraud_queries.cypher  # Reusable fraud analysis queries
├── backendservices/              # DO NOT TOUCH
├── frontendservices/             # DO NOT TOUCH
├── graph-visualizer/             # DO NOT TOUCH
├── compose.yaml                  # ADD your services here
└── init-db.sql                   # DO NOT MODIFY existing tables
```

---

**Questions?** Reach out to your team. Remember: you are **adding** to the system, not
changing it. If something in the existing code needs to change, coordinate with Partner A
before making any modifications.
