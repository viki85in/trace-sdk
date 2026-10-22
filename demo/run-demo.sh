#!/usr/bin/env bash
# Sends a mix of successful and failing orders for two tenants.
set -euo pipefail
base=${1:-http://localhost:8080}
send() { curl -s -o /dev/null -w "%{http_code} " -X POST "$base/orders?$1"; }
for i in 1 2 3; do send "tenant=acme&org=00Dacme0001&items=$i"; done
for i in 1 2; do send "tenant=globex&org=00Dglob0001&items=4"; done
send "tenant=acme&org=00Dacme0001&items=2&failPayment=true"
send "tenant=globex&org=00Dglob0001&items=1&failPayment=true"
echo
echo "Open http://localhost:16686 and pick service 'orders-demo'."
