import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState
} from 'react'
import { useAccount, WagmiProvider } from 'wagmi'
import {
  BitcoinAccount,
  BitcoinConnector,
  BitcoinConnectorEvent
} from 'contexts/bitcoin'
import { wagmiConfig } from 'wagmiConfig'
import Spinner from 'components/common/Spinner'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import { NetworkType } from 'apiClient'
import { disconnect as disconnectEvmWallet } from '@wagmi/core'
import BtcSvg from 'assets/btc.svg'
import { authorizeWallet } from 'walletAuthorization'

export interface ConnectedBitcoinWallet {
  networkType: 'Bitcoin'
  name: string
  address: string
  icon: string
  disconnect: () => void
}

export interface ConnectedEvmWallet {
  networkType: 'Evm'
  name: string
  address: string
  icon: string
  chainId: number
  change: () => void
}

export type ConnectedWallet = ConnectedBitcoinWallet | ConnectedEvmWallet

export const WalletContext = createContext<{
  connect: (networkType: NetworkType) => void
  isConnected: (networkType: NetworkType) => boolean
  connected: ConnectedWallet[]
  primary: ConnectedWallet | null
  forNetwork: (networkType: NetworkType) => ConnectedWallet | null
} | null>(null)

function getGlobal(name: string) {
  const item = window.localStorage.getItem(`funkybit-wallet-${name}`)
  if (item === null || item === 'null') {
    return null
  } else {
    return JSON.parse(item)
  }
}

function setGlobal(
  name: string,
  value: string | number | BitcoinAccount | null | PrimaryWallet
) {
  window.localStorage.setItem(
    `funkybit-wallet-${name}`,
    value !== null ? JSON.stringify(value) : 'null'
  )
}

export function getGlobalBitcoinAccount(): BitcoinAccount | null {
  const bitcoinAccount = getGlobal('bitcoinAccount')

  if (!bitcoinAccount) {
    return null
  }
  return bitcoinAccount
}

type PrimaryWallet = {
  networkType: NetworkType
  address: string
  chainId: number
}

export function getGlobalPrimaryWallet(): PrimaryWallet | null {
  return getGlobal('primaryWallet')
}

function setGlobalPrimaryWallet(wallet: PrimaryWallet | null) {
  setGlobal('primaryWallet', wallet)
}

function setGlobalBitcoinAccount(account: BitcoinAccount | null) {
  setGlobal('bitcoinAccount', account)
}

export function disconnectWallet(networkType: NetworkType) {
  switch (networkType) {
    case 'Bitcoin':
      BitcoinConnector.disconnect()
      break
    case 'Evm':
      disconnectEvmWallet(wagmiConfig)
      break
  }
}

function WalletProviderInternal({ children }: { children: React.ReactNode }) {
  const { open: openWalletConnectModal } = useWeb3Modal()

  const [bitcoinAccount, _setBitcoinAccount] = useState<BitcoinAccount | null>(
    getGlobalBitcoinAccount()
  )

  function setBitcoinAccount(newValue: BitcoinAccount | null) {
    setGlobalBitcoinAccount(newValue)
    _setBitcoinAccount(newValue)
  }

  const [primaryWallet, _setPrimaryWallet] = useState<PrimaryWallet | null>(
    getGlobalPrimaryWallet()
  )

  function setPrimaryWallet(newValue: PrimaryWallet | null) {
    setGlobalPrimaryWallet(newValue)
    _setPrimaryWallet(newValue)
  }

  const evmAccount = useAccount()

  const connected = useMemo(() => {
    const wallets: ConnectedWallet[] = []

    if (evmAccount?.status === 'connected' && evmAccount.connector.icon) {
      wallets.push({
        name: evmAccount.connector.name,
        networkType: 'Evm',
        address: evmAccount.address,
        icon: evmAccount.connector.icon,
        chainId: evmAccount.chainId,
        change: function () {
          openWalletConnectModal({ view: 'Account' }).then(() => {})
        }
      })
    }
    if (bitcoinAccount?.address !== undefined) {
      wallets.push({
        name: 'Bitcoin',
        networkType: 'Bitcoin',
        address: bitcoinAccount.address,
        icon: BtcSvg,
        disconnect: function () {
          disconnectWallet('Bitcoin')
        }
      })
    }
    return wallets
  }, [evmAccount, bitcoinAccount, openWalletConnectModal])

  const primary = useMemo(() => {
    return (
      connected.find(
        (cw) =>
          cw.networkType == primaryWallet?.networkType &&
          cw.address == primaryWallet?.address
      ) || null
    )
  }, [connected, primaryWallet])

  function connect(networkType: NetworkType) {
    if (networkType === 'Evm') {
      openWalletConnectModal({ view: 'Connect' }).then(() => {})
    } else if (networkType === 'Bitcoin') {
      BitcoinConnector.connect()
    }
  }

  function isConnected(networkType: NetworkType): boolean {
    return (
      (networkType == 'Evm' && evmAccount?.status === 'connected') ||
      (networkType == 'Bitcoin' && bitcoinAccount !== null)
    )
  }

  function forNetwork(networkType: NetworkType): ConnectedWallet | null {
    return connected.find((w) => w.networkType === networkType) || null
  }

  const handleBitcoinConnectorEvent = useCallback(
    (event: BitcoinConnectorEvent) => {
      switch (event._kind) {
        case 'connected':
          switch (primaryWallet?.networkType) {
            case undefined:
            case null:
              // no primary wallet yet, so set bitcoin to it
              setPrimaryWallet({
                networkType: 'Bitcoin',
                address: event.accounts[0].address,
                chainId: 0
              })
              setBitcoinAccount(event.accounts[0])
              break
            case 'Evm':
              // primary wallet is evm, now also bitcoin wallet was connected, so store it
              setBitcoinAccount(event.accounts[0])
              break
            case 'Bitcoin':
              // bitcoin is already the primary wallet, make sure the current address is still in the account list
              if (
                event.accounts.find(
                  (a) => a.address == primaryWallet?.address
                ) === undefined
              ) {
                setPrimaryWallet(null)
                setBitcoinAccount(null)
              }
              break
          }
          break
        case 'disconnected':
          setBitcoinAccount(null)
          if (primaryWallet?.networkType == 'Bitcoin') {
            setPrimaryWallet(null)
            if (evmAccount.status === 'connected') {
              setPrimaryWallet({
                networkType: 'Evm',
                address: evmAccount.address,
                chainId: evmAccount.chainId
              })
            }
          }
      }
    },
    [evmAccount, primaryWallet]
  )

  useEffect(() => {
    BitcoinConnector.subscribe(handleBitcoinConnectorEvent)
    return () => {
      BitcoinConnector.unsubscribe(handleBitcoinConnectorEvent)
    }
  }, [handleBitcoinConnectorEvent])

  // handle EVM wallet connect/disconnect events
  useEffect(() => {
    async function onConnected() {
      switch (primaryWallet?.networkType) {
        case undefined:
        case null:
          // case 'Evm':
          setPrimaryWallet({
            networkType: 'Evm',
            address: evmAccount.address!,
            chainId: evmAccount.chainId!
          })
          break
      }
    }

    function onDisconnected() {
      if (primaryWallet?.networkType == 'Evm') {
        setPrimaryWallet(null)
        if (bitcoinAccount != null) {
          setPrimaryWallet({
            networkType: 'Bitcoin',
            address: bitcoinAccount.address,
            chainId: 0
          })
        }
      }
    }

    if (evmAccount.status === 'disconnected') {
      onDisconnected()
    } else if (evmAccount.status === 'connected') {
      onConnected()
    }
  }, [evmAccount, bitcoinAccount, primaryWallet])

  // This has to be done with effect that reacts on evmAccount and bitcoinAccount changes because otherwise
  // when done in wallet connected event handler the evmAccount might be in reconnecting state, and it will lead to a failure
  useEffect(() => {
    const primaryNetworkType = primaryWallet?.networkType
    if (
      evmAccount.status === 'connected' &&
      bitcoinAccount &&
      primaryNetworkType
    ) {
      authorizeWallet(
        primaryNetworkType,
        bitcoinAccount,
        evmAccount,
        disconnectWallet
      )
    }
  }, [evmAccount, bitcoinAccount, primaryWallet])

  return (
    <WalletContext.Provider
      value={{
        connect,
        isConnected,
        connected,
        primary,
        forNetwork
      }}
    >
      <>{children}</>
    </WalletContext.Provider>
  )
}

export function WalletProvider({ children }: { children: React.ReactNode }) {
  return wagmiConfig ? (
    <WagmiProvider config={wagmiConfig}>
      <WalletProviderInternal>{children}</WalletProviderInternal>
    </WagmiProvider>
  ) : (
    <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
      <Spinner />
    </div>
  )
}

export type Wallets = {
  connect: (networkType: NetworkType) => void
  isConnected: (networkType: NetworkType) => boolean
  connected: ConnectedWallet[]
  primary: ConnectedWallet | null
  forNetwork: (networkType: NetworkType) => ConnectedWallet | null
}

export function useWallets(): Wallets {
  const context = useContext(WalletContext)
  if (!context) {
    throw Error(
      'No wallet context found, please make sure the component using this hook is wrapped into WalletProvider'
    )
  }

  const { connect, isConnected, connected, primary, forNetwork } = context

  return {
    connect,
    isConnected,
    connected,
    primary,
    forNetwork
  }
}
