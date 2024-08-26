# How to reset demo env

Prepare python env
```
cd scripts
test -d venv || python3 -m venv venv
. ./venv/bin/activate && pip install --upgrade pip && pip install -r requirements.txt
```

Prepare ssh conf (`.ssh/config`)
```
Host demo-bastion.funkybit.fun
User ec2-user
IdentityFile ~/.ssh/chainring_bastion_id_rsa

Host demo-baregate
Hostname 10.10.3.207
User ec2-user
IdentityFile ~/.ssh/baregate_id_rsa
```

## Stop services
```
python3 ecs-deploy.py stop --env demo --services mocker,api,telegrambot,ring,sequencer,garp
```

## Truncate data
Connect to sequencer's baregate instance and drop queues
```
ssh -J demo-bastion.funkybit.fun demo-baregate

cd /data/queues/demo/
sudo rm -rf *
exit
```

Connect to `bastion` and bind database to local port using port forwarding
```
ssh -L 5556:demo-db-cluster.cluster-cpwwaa4iqa1s.us-east-2.rds.amazonaws.com:5432 demo-bastion.funkybit.fun
```

and then truncate db tables using favourite SQL editor. In this way OHLC records will be preserved.
```
-- truncate table telegram_bot_user_wallet CASCADE;
-- truncate table telegram_bot_user CASCADE;
-- truncate table broadcaster_job CASCADE;
-- truncate table order_execution CASCADE;
-- truncate table trade CASCADE;
-- truncate table "order" CASCADE;
-- truncate table order_book_snapshot;
-- truncate table chain_settlement_batch CASCADE;
-- truncate table settlement_batch CASCADE;
-- truncate table deposit CASCADE;
-- truncate table withdrawal CASCADE;
-- truncate table blockchain_transaction CASCADE;
-- truncate table blockchain_nonce CASCADE;
-- truncate table balance_log CASCADE;
-- truncate table balance CASCADE;
-- truncate table wallet CASCADE;
-- truncate table deployed_smart_contract CASCADE;
-- truncate table faucet_drip;
-- truncate table block;
-- truncate table "limit";
-- truncate table telegram_mini_app_user_reward;
-- truncate table telegram_mini_app_game_reaction_time;
-- truncate table telegram_mini_app_user cascade;
-- truncate table wallet_linked_signer;
-- truncate table bitcoin_wallet_state;
-- truncate table arch_state_utxo_log;
-- truncate table arch_state_utxo;
-- delete from key_value_store where key = 'LastProcessedOutputIndex';
```

## Reset anvil (if necessary)

First stop anvil and otterscan:
```
python3 ecs-deploy.py stop --env demo --services anvil,anvil2,otterscan,otterscan2
```

Next, in `terraform/demo/main.tf` change the `mount_efs_volume = true` lines to `mount_efs_volume = false` and run `terraform apply`.  
Then, in `terraform/demo/main.tf` change the `mount_efs_volume = false` lines back to `mount_efs_volume = true` and re-run `terraform apply`.

Now, upgrade anvil so it gets the new efs volume in its task config, and then start anvil and otterscan:

```
python3 ecs-deploy.py upgrade --env demo --services anvil,anvil2
python3 ecs-deploy.py start --env demo --services anvil,anvil2
python3 ecs-deploy.py start --env demo --services otterscan,otterscan2
```

Finally, null out any symbol contract addresses in the DB so that they get redeployed
```
-- update symbol set contract_address = null;
```

## Start & seed
Start ring and sequencer
```
python3 ecs-deploy.py start --env demo --services ring
python3 ecs-deploy.py start --env demo --services sequencer,garp
```

Bind sequencer and database to localhost via `bastion`
```
ssh -L 5556:demo-db-cluster.cluster-cpwwaa4iqa1s.us-east-2.rds.amazonaws.com:5432 -L 5338:demo-sequencer.funkybit.fun:5337 demo-bastion.funkybit.fun
```

Seed database, Anvil and Sequencer 
```
export DB_HOST="localhost"
export DB_PORT="5556"
export DB_PASSWORD="DEMO_DB_CLUSTER_PASSWORD"

export EVM_CHAINS="Bitlayer,Botanix"
export EVM_NETWORK_URL_Bitlayer="https://demo-anvil.funkybit.fun"
export EVM_NETWORK_URL_Botanix="https://demo-anvil2.funkybit.fun"

export SEQUENCER_HOST_NAME="localhost"
export SEQUENCER_PORT="5338"
export MAKER_FEE_RATE=0.0001
export TAKER_FEE_RATE=0.0002

make db_seed
```

Start api, telegrambot, mocker
```
python3 ecs-deploy.py start --env demo --services api,telegrambot
python3 ecs-deploy.py start --env demo --services mocker
```

Close ssh connections. Cleanup env values.

Inform colleagues about necessity to clean activity data in Metamask
