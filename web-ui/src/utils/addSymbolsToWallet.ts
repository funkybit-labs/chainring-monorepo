import { apiClient } from 'apiClient'

export async function addNewSymbolsToWallet() {
  const config = await apiClient.getAccountConfiguration()
  config.newSymbols?.map(async (sym) => {
    if (
      await window.ethereum?.request({
        method: 'wallet_watchAsset',
        params: {
          type: 'ERC20',
          options: {
            address: sym.contractAddress,
            // TODO: CHAIN-345 - improve cross-chain symbology
            symbol: sym.name.replace(new RegExp('[0-9]+$', ''), ''),
            decimals: sym.decimals,
            image: sym.iconUrl
          }
        }
      })
    ) {
      await apiClient.markSymbolAsAdded(undefined, {
        params: { symbolName: sym.name }
      })
    }
  })
}
