#!/bin/sh

# Build a json from container init params
PARAMS=$(jq -n \
  --arg erigonURL "$ERIGON_URL" \
  --arg beaconAPI "$BEACON_API_URL" \
  --arg assetsURLPrefix "" \
  --arg experimental "$OTS2" \
  --arg netName "$NET_NAME" \
  --arg nativeCurrencyName "$NATIVE_CURRENCY_NAME" \
  '{
    erigonURL: $erigonURL,
    beaconAPI: $beaconAPI,
    assetsURLPrefix: $assetsURLPrefix,
    experimental: $experimental,
    branding: {
      siteName: "funkybit",
      networkTitle: $netName,
      hideAnnouncements: false
    },
    chainInfo: { 
      name: $netName,
      faucets: [],
      nativeCurrency: {
        name: $nativeCurrencyName,
        symbol: "BTC",
        decimals: 18
      }
    }
  }')

# Overwrite base image config.json with our own and let nginx do the rest
echo $PARAMS > /usr/share/nginx/html/config.json
exec nginx -g "daemon off;"
