# How to reset demo env

Prepare python env
```
cd scripts
test -d venv || python3 -m venv venv
. ./venv/bin/activate && pip install --upgrade pip && pip install -r requirements.txt
```

Prepare ssh conf (`.ssh/config`)
```
Host demo-bastion.chainring.co
User ec2-user
IdentityFile ~/.ssh/chainring_bastion_id_rsa

Host demo-baregate
Hostname 10.10.3.207
User ec2-user
IdentityFile ~/.ssh/baregate_id_rsa
```

## Stop services
```
python3 ecs-deploy.py stop --env demo --services mocker,api,telegrambot,ring,sequencer
```

## Truncate data
Connect to sequencer's baregate instance and drop queues
```
ssh -J demo-bastion.chainring.co demo-baregate

cd /data/queues/demo/
sudo rm -rf *
exit
```

Connect to `bastion` and bind database to local port using port forwarding
```
ssh -L 5556:demo-db-cluster.cluster-cpwwaa4iqa1s.us-east-2.rds.amazonaws.com:5432 demo-bastion.chainring.co
```

and then truncate db tables using favourite SQL editor. In this way OHLC records will be preserved.
```
-- truncate table telegram_bot_user_wallet CASCADE;
-- truncate table telegram_bot_user CASCADE;
-- truncate table broadcaster_job CASCADE;
-- truncate table order_execution CASCADE;
-- truncate table trade CASCADE;
-- truncate table "order" CASCADE;
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
-- delete from key_value_store where key = 'LastProcessedOutputIndex';
```

## Start & seed
Start ring and sequencer
```
python3 ecs-deploy.py start --env demo --services ring
python3 ecs-deploy.py start --env demo --services sequencer
```

Bind sequencer and database to localhost via `bastion`
```
ssh -L 5556:demo-db-cluster.cluster-cpwwaa4iqa1s.us-east-2.rds.amazonaws.com:5432 -L 5338:demo-sequencer.chainring.co:5337 demo-bastion.chainring.co
```

<mark>Make correct fee values are set in `Fixtures.kt`</mark> (CHAIN-292 to set 0.01 and 0.02)
```
    feeRates = FeeRates.fromPercents(maker = 0.01, taker = 0.02)
```

Seed database, Anvil and Sequencer 
```
export DB_HOST="localhost"
export DB_PORT="5556"
export DB_PASSWORD="DEMO_DB_CLUSTER_PASSWORD"

export EVM_CHAINS="Bitlayer,Botanix"
export EVM_NETWORK_URL_Bitlayer="https://demo-anvil.chainring.co"
export EVM_NETWORK_URL_Botanix="https://demo-anvil2.chainring.co"

export SEQUENCER_HOST_NAME="localhost"
export SEQUENCER_PORT="5338"

make db_seed
```

Start api, telegrambot, mocker
```
python3 ecs-deploy.py start --env demo --services api
python3 ecs-deploy.py start --env demo --services telegrambot
python3 ecs-deploy.py start --env demo --services mocker
```

Close ssh connections. Cleanup env values.

Inform colleagues about necessity to clean activity data in Metamask
