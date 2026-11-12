"""Runs one read-only SQL statement on Spark and prints the rows as JSON.

The gateway submits this file with spark-submit, one JVM per call. Keeping the job in a separate
process is deliberate: Spark drags in its own Guava, Netty and Jackson, which cannot coexist with
the gateway's classpath in one JVM.

Arguments: <sql-file> <max-rows>. The result is one marker line followed by one JSON line, so the
gateway can ignore the rest of Spark's log output.
"""

import json
import sys

from pyspark.sql import SparkSession


def main():
    sql_path = sys.argv[1]
    max_rows = int(sys.argv[2])

    with open(sql_path, "r", encoding="utf-8") as handle:
        sql = handle.read()

    spark = (SparkSession.builder
             .appName("mcp-gateway-spark")
             .config("spark.sql.shuffle.partitions", "4")
             .getOrCreate())
    try:
        frame = spark.sql(sql)
        columns = list(frame.columns)
        collected = [list(row) for row in frame.limit(max_rows + 1).collect()]
        truncated = len(collected) > max_rows
        rows = collected[:max_rows]
    finally:
        spark.stop()

    print("###SPARK_RESULT###")
    print(json.dumps({"columns": columns, "rows": rows, "truncated": truncated}, default=str))


if __name__ == "__main__":
    main()