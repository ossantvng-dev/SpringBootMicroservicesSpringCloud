#!/usr/bin/env bash
# Stage 1 of the ELK bootstrap: generate the CA and the Elasticsearch server
# certificate. Runs to completion BEFORE Elasticsearch starts.
#
# Idempotent - certificates are only generated when missing, so restarting the
# stack does not invalidate a cluster that already trusts them.
set -euo pipefail

CERTS=/certs

if [[ ! -f "${CERTS}/ca/ca.crt" ]]; then
  echo "[certs] generating CA"
  elasticsearch-certutil ca --silent --pem --out /tmp/ca.zip
  unzip -q /tmp/ca.zip -d "${CERTS}"
else
  echo "[certs] CA already present, reusing"
fi

if [[ ! -f "${CERTS}/es/es.crt" ]]; then
  echo "[certs] generating Elasticsearch certificate"
  # SANs cover the compose service name, the container name and loopback, so the
  # certificate is valid however a client addresses the node.
  elasticsearch-certutil cert --silent --pem \
    --ca-cert "${CERTS}/ca/ca.crt" \
    --ca-key  "${CERTS}/ca/ca.key" \
    --dns elasticsearch,photo-app-elasticsearch,localhost \
    --ip 127.0.0.1 \
    --name es \
    --out /tmp/certs.zip
  unzip -q /tmp/certs.zip -d "${CERTS}"
else
  echo "[certs] Elasticsearch certificate already present, reusing"
fi

# Elasticsearch runs as uid 1000 and must be able to read these.
chown -R 1000:0 "${CERTS}"
find "${CERTS}" -type d -exec chmod 750 {} \;
find "${CERTS}" -type f -exec chmod 640 {} \;

echo "[certs] done"
