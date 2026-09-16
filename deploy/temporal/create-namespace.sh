#!/bin/sh
set -eu

address="${TEMPORAL_ADDRESS:-temporal:7233}"
namespace="${DEFAULT_NAMESPACE:-default}"

until temporal operator cluster health --address "$address" >/dev/null 2>&1; do sleep 2; done
if ! temporal operator namespace describe -n "$namespace" --address "$address" >/dev/null 2>&1; then
  temporal operator namespace create -n "$namespace" --address "$address"
fi
