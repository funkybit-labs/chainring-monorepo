#!/bin/bash

source .local_env

if [ ! -d lib ]; then
  ln -s contracts/lib lib
fi

declare -a coin_types=("USDC" "DAI")

for coin_type in "${coin_types[@]}"
do
  DEPLOYED_ADDRESS=`TOKEN_SYMBOL=$coin_type forge script ./scripts/local/MockErc20Token.s.sol --rpc-url $RPC_URL --out build/contracts --broadcast --private-key $PRIVATE_KEY -vvv 2>&1 | grep "deployAddress" | jq -r '.deployAddress'`
  echo "Deployed ERC20 address for $coin_type is $DEPLOYED_ADDRESS"
  PGPASSWORD=$PGPASSWORD psql -U chainring -d chainring -h localhost -c "delete from erc20_token where symbol='$coin_type'; insert into erc20_token (guid, created_at, created_by, name, symbol, chain_id, address, decimals) values ('`uuidgen`', 'now()', 'local_init', '$coin_type Coin', '$coin_type', (SELECT id FROM chain WHERE name = 'chainring-dev'), '$DEPLOYED_ADDRESS', 18);"
done

unlink lib
