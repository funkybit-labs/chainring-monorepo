#!/bin/bash
set -e

ENV=${1:?"Specify an env (either test or demo)"}

if [ "$ENV" != "test" ] && [ "$ENV" != "demo" ]; then
  echo "Invalid env"
  exit 1
fi

# Wait for nodes to be ready.

while :
do
  IS_READY=$(curl -sLX POST -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":"id","method":"all_nodes_ready","params":[]}' https://$ENV-arch.funkybit.fun/ | jq .result)
  if [ "$IS_READY" = true ] ; then
    break;
  fi
  echo "Nodes are not ready... Will try again in 1 seconds."
  sleep 1
done

echo "Nodes are ready! Running start_key_exchange"

curl -sLX POST \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"id","method":"start_key_exchange","params":[]}' \
  https://$ENV-arch.funkybit.fun/

echo -e "\nRunning start_dkg"

curl -sLX POST \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"id","method":"start_dkg","params":[]}' \
  https://$ENV-arch.funkybit.fun/

echo -e "\nDone!"
