import { useAccount, useConfig } from 'wagmi'
import { useMemo } from 'react'

export function useValidChain() {
  const config = useConfig()
  const wallet = useAccount()
  return useMemo(() => {
    return config.chains.find((c) => c.id === wallet.chainId)
  }, [config, wallet])
}
