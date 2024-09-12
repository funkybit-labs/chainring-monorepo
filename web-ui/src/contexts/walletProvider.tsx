import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState
} from 'react'
import { useAccount, UseAccountReturnType, WagmiProvider } from 'wagmi'
import {
  BitcoinAccount,
  BitcoinProvider,
  useBitcoinWallet
} from 'contexts/bitcoin'
import { wagmiConfig } from 'wagmiConfig'
import Spinner from 'components/common/Spinner'
import { useWeb3Modal, useWeb3ModalEvents } from '@web3modal/wagmi/react'
import SatsConnect from 'sats-connect'
import { useEffectOnce } from 'react-use'
import { apiClient, authorizeWalletApiClient, NetworkType } from 'apiClient'
import { signTypedData } from '@wagmi/core'
import { signAuthToken } from 'auth'
import BtcSvg from 'assets/btc.svg'

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
  value: string | number | BitcoinAccount | null
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

  return {
    ...bitcoinAccount,
    signMessage: async (address, message) => {
      const result = await SatsConnect.request('signMessage', {
        address,
        message
      })
      if (result.status === 'success') {
        return result.result.signature
      } else {
        return ''
      }
    }
  }
}
function setGlobalBitcoinAccount(account: BitcoinAccount | null) {
  setGlobal('bitcoinAccount', account)
}

export function getGlobalPrimaryAddress(): string | null {
  return getGlobal('primaryAddress')
}
function setGlobalPrimaryAddress(address: string | null) {
  setGlobal('primaryAddress', address)
}

export function getGlobalPrimaryNetworkType(): NetworkType | null {
  return getGlobal('primaryNetworkType')
}
function setGlobalPrimaryNetworkType(networkType: NetworkType | null) {
  setGlobal('primaryNetworkType', networkType)
}

export function getGlobalPrimaryChainId(): number | null {
  return getGlobal('primaryChainId')
}
function setGlobalPrimaryChainId(chainId: number | null) {
  setGlobal('primaryChainId', chainId)
}

function WalletProviderInternal({ children }: { children: React.ReactNode }) {
  const { open: openWalletConnectModal } = useWeb3Modal()
  const { data: web3ModalEvent } = useWeb3ModalEvents()

  const [bitcoinAccount, setBitcoinAccount] = useState<BitcoinAccount | null>(
    getGlobalBitcoinAccount()
  )

  const bitcoinWallet = useBitcoinWallet({
    eventHandler: useCallback((event) => {
      switch (event._kind) {
        case 'connected':
          switch (getGlobalPrimaryNetworkType()) {
            case null:
              // no primary wallet yet, so set bitcoin to it
              setGlobalPrimaryAddress(event.accounts[0].address)
              setGlobalBitcoinAccount(event.accounts[0])
              setGlobalPrimaryNetworkType('Bitcoin')
              setGlobalPrimaryChainId(0)
              setBitcoinAccount(event.accounts[0])
              break
            case 'Evm':
              // primary wallet is evm, now also bitcoin wallet was connected, so store it
              setGlobalBitcoinAccount(event.accounts[0])
              setBitcoinAccount(event.accounts[0])
              break
            case 'Bitcoin':
              // bitcoin is already the primary wallet, make sure the current address is still in the account list
              if (
                event.accounts.find(
                  (a) => a.address === getGlobalPrimaryAddress()
                ) === undefined
              ) {
                clearGlobalPrimary()
                setGlobalBitcoinAccount(null)
                setBitcoinAccount(null)
              } else {
                setGlobalPrimaryChainId(0)
              }
              break
          }
          break
        case 'disconnected':
          setGlobalBitcoinAccount(null)
          setBitcoinAccount(null)
          if (getGlobalPrimaryNetworkType() == 'Bitcoin') {
            clearGlobalPrimary()
          }
      }
    }, [])
  })

  const evmAccount = useAccount()

  const globalPrimaryNetworkType = getGlobalPrimaryNetworkType()
  const primaryNetworkType = useMemo(() => {
    if (globalPrimaryNetworkType) {
      return globalPrimaryNetworkType
    }

    return evmAccount?.status === 'connected'
      ? 'Evm'
      : bitcoinAccount?.address
        ? 'Bitcoin'
        : null
  }, [globalPrimaryNetworkType, evmAccount, bitcoinAccount])

  const primaryAddress = useMemo(() => {
    switch (primaryNetworkType) {
      case 'Evm':
        return evmAccount?.address
      case 'Bitcoin':
        return bitcoinAccount?.address
    }
  }, [primaryNetworkType, evmAccount, bitcoinAccount])

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
          bitcoinWallet.disconnect()
        }
      })
    }
    return wallets
  }, [evmAccount, bitcoinAccount, bitcoinWallet, openWalletConnectModal])

  const primary = useMemo(() => {
    return connected.find((cw) => cw.networkType == primaryNetworkType) || null
  }, [primaryNetworkType, connected])

  useEffectOnce(() => {
    if (globalPrimaryNetworkType != primaryNetworkType) {
      setGlobalPrimaryNetworkType(primaryNetworkType)
      setGlobalPrimaryAddress(primaryAddress ?? null)
    }
  })

  function connect(networkType: NetworkType) {
    if (networkType === 'Evm') {
      openWalletConnectModal({ view: 'Connect' }).then(() => {})
    } else if (networkType === 'Bitcoin') {
      bitcoinWallet.connect()
    }
  }

  function isConnected(networkType: NetworkType): boolean {
    return (
      (networkType == 'Evm' && evmAccount?.status === 'connected') ||
      (networkType == 'Bitcoin' && bitcoinAccount !== null)
    )
  }

  // handle EVM wallet connect/disconnect events
  useEffect(() => {
    async function onConnected() {
      switch (getGlobalPrimaryNetworkType()) {
        case null:
        case 'Evm':
          setGlobalPrimaryAddress(evmAccount.address!)
          setGlobalPrimaryChainId(evmAccount.chainId!)
          setGlobalPrimaryNetworkType('Evm')
          break
      }
    }

    function onDisconnected() {
      if (getGlobalPrimaryNetworkType() == 'Evm') {
        clearGlobalPrimary()
      }
    }

    switch (web3ModalEvent.event) {
      case 'CONNECT_SUCCESS':
        if (evmAccount.status === 'connected') {
          onConnected()
        }
        break
      case 'DISCONNECT_SUCCESS':
        onDisconnected()
        break
      case 'MODAL_CLOSE':
        if (evmAccount.status === 'disconnected') {
          onDisconnected()
        } else if (evmAccount.status === 'connected') {
          onConnected()
        }
        break
    }
  }, [web3ModalEvent, evmAccount])

  const authorizeBitcoinWallet = useCallback(
    async (
      bitcoinAccount: BitcoinAccount,
      evmAccount: UseAccountReturnType
    ) => {
      const accountConfig = await apiClient.getAccountConfiguration()
      if (accountConfig.authorizedAddresses.length == 0) {
        const bitcoinAddress = bitcoinAccount.address
        const evmAddress = evmAccount.address!
        const chainId = evmAccount.chainId!
        const timestamp = new Date().toISOString()

        const bitcoinWalletAuthToken = await signAuthToken(bitcoinAddress, 0)

        const commonMessage = `[funkybit] Please sign this message to authorize Bitcoin wallet ${bitcoinAddress}. This action will not cost any gas fees.`
        const evmMessage = {
          message: commonMessage,
          address: evmAddress,
          authorizedAddress: bitcoinAddress,
          chainId: chainId,
          timestamp: timestamp
        }
        const evmSignature = await signTypedData(wagmiConfig, {
          domain: {
            name: 'funkybit',
            chainId: chainId
          },
          types: {
            EIP712Domain: [
              { name: 'name', type: 'string' },
              { name: 'chainId', type: 'uint32' }
            ],
            Authorize: [
              { name: 'message', type: 'string' },
              { name: 'address', type: 'string' },
              { name: 'authorizedAddress', type: 'string' },
              { name: 'chainId', type: 'uint32' },
              { name: 'timestamp', type: 'string' }
            ]
          },
          message: evmMessage,
          primaryType: 'Authorize'
        })

        await authorizeWallet(
          {
            address: bitcoinAddress,
            authToken: bitcoinWalletAuthToken
          },
          {
            address: evmAddress.toLowerCase(),
            chainId: chainId,
            signature: evmSignature,
            timestamp: timestamp
          }
        )
      }
    },
    []
  )

  const authorizeEvmWallet = useCallback(
    async (
      bitcoinAccount: BitcoinAccount,
      evmAccount: UseAccountReturnType
    ) => {
      const accountConfig = await apiClient.getAccountConfiguration()
      if (accountConfig.authorizedAddresses.length == 0) {
        const bitcoinAddress = bitcoinAccount.address
        const evmAddress = evmAccount.address!
        const chainId = evmAccount.chainId!
        const timestamp = new Date().toISOString()

        const evmWalletAuthToken = await signAuthToken(evmAddress, chainId)

        const commonMessage = `[funkybit] Please sign this message to authorize EVM wallet ${evmAddress.toLowerCase()}. This action will not cost any gas fees.`
        const bitcoinMessage = `${commonMessage}\nAddress: ${bitcoinAddress}, Timestamp: ${timestamp}`
        const bitcoinSignature = await bitcoinAccount!.signMessage(
          bitcoinAddress,
          bitcoinMessage
        )

        await authorizeWallet(
          {
            address: evmAddress,
            authToken: evmWalletAuthToken
          },
          {
            address: bitcoinAddress,
            chainId: 0,
            signature: bitcoinSignature,
            timestamp: timestamp
          }
        )
      }
    },
    []
  )

  async function authorizeWallet(
    authorizedWallet: { address: string; authToken: string },
    authorizingWallet: {
      address: string
      chainId: number
      signature: string
      timestamp: string
    }
  ) {
    try {
      await authorizeWalletApiClient.authorizeWallet(
        {
          authorizedAddress: authorizedWallet.address,
          chainId: authorizingWallet.chainId,
          address: authorizingWallet.address,
          timestamp: authorizingWallet.timestamp,
          signature: authorizingWallet.signature
        },
        {
          headers: { Authorization: `Bearer ${authorizedWallet.authToken}` }
        }
      )
    } catch (e) {
      console.log(e)
      alert('Failed to authorize wallet')
    }
  }

  // This has to be done with effect that reacts on evmAccount and bitcoinAccount changes because otherwise
  // when done in wallet connected event handler the evmAccount might be in reconnecting state, and it will lead to a failure
  useEffect(() => {
    if (evmAccount.status === 'connected' && bitcoinAccount) {
      if (getGlobalPrimaryNetworkType() === 'Evm') {
        authorizeBitcoinWallet(bitcoinAccount, evmAccount)
      } else {
        authorizeEvmWallet(bitcoinAccount, evmAccount)
      }
    }
  }, [evmAccount, bitcoinAccount, authorizeBitcoinWallet, authorizeEvmWallet])

  function clearGlobalPrimary() {
    setGlobalPrimaryAddress(null)
    setGlobalPrimaryChainId(null)
    setGlobalPrimaryNetworkType(null)
  }

  return (
    <WalletContext.Provider
      value={{
        connect,
        isConnected,
        connected,
        primary
      }}
    >
      <>{children}</>
    </WalletContext.Provider>
  )
}

export function WalletProvider({ children }: { children: React.ReactNode }) {
  return wagmiConfig ? (
    <BitcoinProvider>
      <WagmiProvider config={wagmiConfig}>
        <WalletProviderInternal>{children}</WalletProviderInternal>
      </WagmiProvider>
    </BitcoinProvider>
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
}

export function useWallets(): Wallets {
  const context = useContext(WalletContext)
  if (!context) {
    throw Error(
      'No wallet context found, please make sure the component using this hook is wrapped into WalletProvider'
    )
  }

  const { connect, isConnected, connected, primary } = context

  return {
    connect,
    isConnected,
    connected,
    primary
  }
}
