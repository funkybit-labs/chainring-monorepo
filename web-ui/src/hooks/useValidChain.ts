import { useConfig } from 'wagmi'
import { useMemo } from 'react'
import { useWallets } from 'contexts/walletProvider'

export function useValidChain() {
  const config = useConfig()
  const wallets = useWallets()

  return useMemo(() => {
    const primaryWallet = wallets.primary
    if (primaryWallet) {
      switch (primaryWallet.networkType) {
        case 'Evm':
          return config.chains.find((c) => c.id === primaryWallet.chainId)
        case 'Bitcoin':
          return 'Bitcoin'
      }
    } else {
      return undefined
    }
  }, [config, wallets])
}
