#!/bin/sh
# Creates the bucket the adapter's health check needs. Idempotent: 409 means it already exists.
set -eu

endpoint="http://seaweedfs:8333"
bucket="${MCP_S3_BUCKET:-mcp-demo}"

until curl -fsS -o /dev/null "$endpoint/"; do
    sleep 1
done

code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$endpoint/$bucket")
case "$code" in
    200|409) echo "bucket $bucket ready ($code)" ;;
    *) echo "create bucket failed: $code" >&2; exit 1 ;;
esac