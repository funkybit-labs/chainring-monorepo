## How to run locally

Build anvil docker image

```
make anvil_image
```

Start the containers

```
make start_containers
```

Start web-ui dev server

```
make ui_server
```

Open http://localhost:3000 in your browser

## Tail anvil container logs

```
make anvil_logs
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
   - Enter one of the private keys Anvil prints on the startup, e.g `0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80`
