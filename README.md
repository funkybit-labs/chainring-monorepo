# funkybit

funkybit is a cross-chain DEX with a high-performance, low-latency off-chain sequencer.

## No License

This project is not licensed, and no permissions are granted to use, copy, modify, or distribute its
contents. While the source code is publicly available, you are not authorized to do anything beyond
viewing and forking the repository.

## How to run locally

Build anvil and otterscan docker images

```
make anvil_image
make otterscan_image
```

Install rust and solana
```
curl https://sh.rustup.rs -sSf | sh
curl -sSfL https://release.solana.com/v1.18.18/install | sh
```

Start the containers

```
make start_containers
```

Start web-ui dev server

```
make run_ui
```

Open http://localhost:3000 in your browser

## Tail anvil container logs

```
make anvil_logs
```

Run Sequencer

```
make run_sequencer
```

Seed database, Anvil and Sequencer (requires a running Sequencer)

```
make local_init
```

Before running local_init make sure to update the submodules in the project

```
git submodule update --init --recursive
```

Run backend

```
make run_backend
```

## Connecting a wallet

1. Go to https://metamask.io and follow the instructions to install the browser extension
2. Add local network (served by Anvil) to Metamask:
   - Settings -> Networks -> Add Network -> Add a network manually
   - Put `localhost` as Network name
   - Put `http://localhost:8545` as RPC URL
   - Put `0x539` as Chain ID
   - Put `ETH` as Currency symbol
3. Add a local account to Metamask:
   - In account selector click "+ Add account or hardware wallet"
   - Select "Import account" option
   - Enter one of the following private keys: `0x4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356`, `0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97`, `0x2a871d0798f97d79848a013d4936a73bf4cc922c825d33c1cf7073dff6d409c6`
4. Let Metamask know about ERC20Mock coins:
   - Open Metamask, go to "Tokens" -> "Import tokens"
   - Enter USDC contract addresses e.g `0x7ef8E99980Da5bcEDcF7C10f41E55f759F6A174B` and click "Next"
   - Repeat for DAI (`0x239745750870104a7EC6126c89156D773088286c`)
