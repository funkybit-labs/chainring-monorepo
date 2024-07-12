#!/bin/bash

: "${CHAIN_ID:=31338}"

exec /root/.foundry/bin/anvil --block-time 1 --host 0.0.0.0 --port 8545 --state /data/anvil_state.json --transaction-block-keeper 10000 --chain-id $CHAIN_ID
