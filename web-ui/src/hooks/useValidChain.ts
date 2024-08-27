import { useConfig } from 'wagmi'
import { useMemo } from 'react'
import { useWallet } from 'contexts/walletProvider'

export function useValidChain() {
  const config = useConfig()
  const wallet = useWallet()
  return useMemo(() => {
    switch (wallet.primaryCategory) {
      case 'evm':
        return config.chains.find((c) => c.id === wallet.evmAccount?.chainId)
      case 'bitcoin':
        return 'Bitcoin'
      case 'none':
        return undefined
    }
  }, [config, wallet])
}
