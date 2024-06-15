import { useSwitchChain } from 'wagmi'
import { allChains } from 'wagmiConfig'

export function useSwitchToEthChain() {
  const { switchChain } = useSwitchChain()
  return (switchToChainId: number) => {
    const chain = allChains.find((c) => c.id == switchToChainId)
    chain &&
      switchChain({
        addEthereumChainParameter: {
          chainName: chain.name,
          nativeCurrency: chain.nativeCurrency,
          rpcUrls: chain.rpcUrls.default.http,
          blockExplorerUrls: chain.blockExplorers
            ? [chain.blockExplorers.default.url]
            : undefined
        },
        chainId: chain.id
      })
  }
}
