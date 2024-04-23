import { createConfig, http } from 'wagmi'
import { walletConnect, injected, coinbaseWallet } from 'wagmi/connectors'
import { createWeb3Modal } from '@web3modal/wagmi/react'
import { defineChain } from 'viem'

export const walletConnectProjectId = '03908a0893516a0f391370f3a9349b8e'

const walletConnectMetadata = {
  name: 'ChainRing',
  description: 'The first cross-chain DEX built on Bitcoin',
  url: '', // origin must match your domain & subdomain
  icons: ['https://avatars.githubusercontent.com/u/37784886']
}

export const chain = defineChain({
  id: parseInt(import.meta.env.ENV_CHAIN_ID),
  name: import.meta.env.ENV_CHAIN_NAME,
  nativeCurrency: {
    decimals: 18,
    name: import.meta.env.ENV_NATIVE_SYMBOL_NAME,
    symbol: import.meta.env.ENV_NATIVE_SYMBOL
  },
  rpcUrls: {
    default: { http: [import.meta.env.ENV_CHAIN_JSON_RPC_URL] }
  }
})

export const wagmiConfig = createConfig({
  chains: [chain],
  transports: {
    [chain.id]: http()
  },
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

// Make wagmi config more type safe, see https://wagmi.sh/react/typescript#config-types
declare module 'wagmi' {
  interface Register {
    config: typeof wagmiConfig
  }
}

createWeb3Modal({
  wagmiConfig: wagmiConfig,
  projectId: walletConnectProjectId,
  enableAnalytics: true, // Optional - defaults to your Cloud configuration
  enableOnramp: true // Optional - false as default
})
