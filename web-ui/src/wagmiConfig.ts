import { Config, createConfig, http } from 'wagmi'
import { coinbaseWallet, injected, walletConnect } from 'wagmi/connectors'
import { createWeb3Modal } from '@web3modal/wagmi/react'
import { Chain, defineChain } from 'viem'
import { apiClient, ConfigurationApiResponseSchema } from 'apiClient'

const walletConnectProjectId = '03908a0893516a0f391370f3a9349b8e'

const walletConnectMetadata = {
  name: 'ChainRing',
  description: 'The first cross-chain DEX built on Bitcoin',
  url: '', // origin must match your domain & subdomain
  icons: ['https://avatars.githubusercontent.com/u/37784886']
}

export let allChains: [Chain, ...Chain[]]
export let wagmiConfig: Config

export const initializeWagmiConfig = async () => {
  const apiConfig = ConfigurationApiResponseSchema.parse(
    await apiClient.getConfiguration()
  )

  const chains = apiConfig.chains.map((chain) => {
    const nativeSymbol = chain.symbols.filter(
      (symbol) => symbol.contractAddress == null
    )[0]

    return defineChain({
      id: chain.id,
      name: chain.name,
      nativeCurrency: {
        decimals: 18,
        name: nativeSymbol.description,
        symbol: nativeSymbol.name
      },
      rpcUrls: {
        default: { http: [chain.jsonRpcUrl] }
      },
      blockExplorers: {
        default: {
          name: chain.blockExplorerNetName,
          url: chain.blockExplorerUrl
        }
      }
    })
  })

  if (isNonEmptyArray(chains)) {
    allChains = chains
  } else {
    throw new Error('No chains available in the configuration')
  }

  wagmiConfig = createConfig({
    chains: allChains,
    transports: chains.reduce(
      (acc: Record<number, ReturnType<typeof http>>, chain: Chain) => {
        acc[chain.id] = http()
        return acc
      },
      {}
    ),
    connectors: [
      walletConnect({
        projectId: walletConnectProjectId,
        metadata: walletConnectMetadata,
        showQrModal: false
      }),
      injected({ shimDisconnect: true }),
      coinbaseWallet({
        appName: walletConnectMetadata.name,
        appLogoUrl: walletConnectMetadata.icons[0]
      })
    ]
  })

  createWeb3Modal({
    wagmiConfig: wagmiConfig,
    projectId: walletConnectProjectId,
    enableAnalytics: true,
    enableOnramp: true
  })
}

function isNonEmptyArray<T>(array: T[]): array is [T, ...T[]] {
  return array.length > 0
}
