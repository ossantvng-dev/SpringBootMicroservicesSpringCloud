#!/usr/bin/env bash
# Stage 2 of the ELK bootstrap: set the kibana_system service account password.
# Runs AFTER Elasticsearch is healthy and before Kibana starts.
#
# The elastic superuser password comes from ELASTIC_PASSWORD at cluster
# bootstrap; kibana_system has no usable password until it is set here.
set -euo pipefail

CA=/certs/ca/ca.crt
ES=https://elasticsearch:9200

echo "[users] setting kibana_system password"
for attempt in $(seq 1 30); do
  code=$(curl -s -o /tmp/out -w '%{http_code}' -X POST \
    --cacert "${CA}" -u "elastic:${ELASTIC_PASSWORD}" \
    -H 'Content-Type: application/json' \
    "${ES}/_security/user/kibana_system/_password" \
    -d "{\"password\":\"${KIBANA_SYSTEM_PASSWORD}\"}" || echo 000)
  if [[ "${code}" == "200" ]]; then
    echo "[users] kibana_system password set"
    exit 0
  fi
  echo "[users] attempt ${attempt}: HTTP ${code}, retrying"
  sleep 5
done

echo "[users] FAILED to set kibana_system password" >&2
cat /tmp/out >&2 || true
exit 1
