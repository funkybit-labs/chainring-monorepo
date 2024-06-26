import { apiClient } from 'apiClient'

export async function addNewSymbolsToWallet() {
  const config = await apiClient.getAccountConfiguration()
  config.newSymbols?.map(async (sym) => {
    const [symbolName, chainId] = sym.name.split(':')
    if (
      await window.ethereum?.request({
        method: 'wallet_watchAsset',
        params: {
          type: 'ERC20',
          options: {
            address: sym.contractAddress,
            symbol: symbolName,
            decimals: sym.decimals,
            image: sym.iconUrl,
            chainId: chainId
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
