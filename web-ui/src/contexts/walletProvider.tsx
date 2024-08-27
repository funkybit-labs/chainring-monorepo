import React, {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState
} from 'react'
import { Config, useAccount, UseAccountReturnType, WagmiProvider } from 'wagmi'
import {
  BitcoinAccount,
  BitcoinProvider,
  useBitcoinWallet
} from 'contexts/bitcoin'
import { wagmiConfig } from 'wagmiConfig'
import Spinner from 'components/common/Spinner'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import SatsConnect from 'sats-connect'
import { useEffectOnce } from 'react-use'

export type WalletCategory = 'evm' | 'bitcoin' | 'none'

export const WalletContext = createContext<{
  connect: (category: WalletCategory) => void
  disconnect: () => void
  changeAccount: () => void
  bitcoinAccount: BitcoinAccount | null
  evmAccount: UseAccountReturnType<Config> | null
  primaryAccount: UseAccountReturnType | BitcoinAccount | null
  primaryCategory: WalletCategory
  primaryAddress: string | undefined
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
  value: string | UseAccountReturnType | BitcoinAccount | null
) {
  window.localStorage.setItem(
    `funkybit-wallet-${name}`,
    value ? JSON.stringify(value) : 'null'
  )
}

export function getGlobalEvmAccount(): UseAccountReturnType | null {
  return getGlobal('evmAccount')
}
function setGlobalEvmAccount(account: UseAccountReturnType | null) {
  setGlobal('evmAccount', account)
}

export function getGlobalBitcoinAccount(): BitcoinAccount | null {
  return {
    ...getGlobal('bitcoinAccount'),
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

export function getGlobalPrimaryAccount():
  | UseAccountReturnType
  | BitcoinAccount
  | null {
  return getGlobal('primaryAccount')
}
function setGlobalPrimaryAccount(
  account: UseAccountReturnType | BitcoinAccount | null
) {
  setGlobal('primaryAccount', account)
}

export function getGlobalPrimaryAddress(): string | null {
  return getGlobal('primaryAddress')
}
function setGlobalPrimaryAddress(address: string | null) {
  setGlobal('primaryAddress', address)
}

export function getGlobalPrimaryCategory(): WalletCategory | null {
  return getGlobal('primaryCategory')
}
function setGlobalPrimaryCategory(category: WalletCategory) {
  setGlobal('primaryCategory', category)
}

function WalletProviderInternal({ children }: { children: React.ReactNode }) {
  const { open: openWalletConnectModal } = useWeb3Modal()

  const [bitcoinAccount, setBitcoinAccount] = useState<BitcoinAccount | null>(
    getGlobalBitcoinAccount()
  )

  const bitcoinWallet = useBitcoinWallet()
  const evmAccount = useAccount()
  const primaryCategory = useMemo(() => {
    return evmAccount?.status === 'connected'
      ? 'evm'
      : bitcoinAccount?.address
        ? 'bitcoin'
        : 'none'
  }, [evmAccount, bitcoinAccount])
  const primaryAccount = useMemo(() => {
    switch (primaryCategory) {
      case 'evm':
        return evmAccount
      case 'bitcoin':
        return bitcoinAccount
    }
    return null
  }, [primaryCategory, evmAccount, bitcoinAccount])
  const primaryAddress = useMemo(() => {
    switch (primaryCategory) {
      case 'evm':
        return evmAccount?.address
      case 'bitcoin':
        return bitcoinAccount?.address
    }
  }, [primaryCategory, evmAccount, bitcoinAccount])

  useEffectOnce(() => {
    setGlobalPrimaryCategory(primaryCategory)
    setGlobalPrimaryAddress(primaryAddress ?? null)
    setGlobalPrimaryAccount(primaryAccount)
  })

  useEffect(() => {
    if (evmAccount.status === 'connected') {
      const category = getGlobalPrimaryCategory()
      switch (category) {
        case null:
        case 'evm':
          setGlobalPrimaryAccount(evmAccount)
          setGlobalPrimaryAddress(evmAccount.address ?? null)
          setGlobalEvmAccount(evmAccount)
          setGlobalPrimaryCategory('evm')
          break
      }
    }
  }, [evmAccount])

  function connect(category: WalletCategory) {
    if (category === 'evm') {
      openWalletConnectModal({ view: 'Connect' }).then(() => {})
    } else if (category === 'bitcoin') {
      bitcoinWallet.connect()
    }
  }

  function disconnect() {
    if (primaryCategory === 'bitcoin') {
      bitcoinWallet.disconnect()
      setGlobalBitcoinAccount(null)
    }
  }

  useEffect(() => {
    switch (primaryCategory) {
      case null:
      case 'none':
        // no primary wallet yet, so set bitcoin to it
        if (bitcoinWallet.accounts.length > 0) {
          setGlobalPrimaryAccount(bitcoinWallet.accounts[0])
          setGlobalPrimaryAddress(bitcoinWallet.accounts[0].address)
          setGlobalBitcoinAccount(bitcoinWallet.accounts[0])
          setGlobalPrimaryCategory('bitcoin')
          setBitcoinAccount(bitcoinWallet.accounts[0])
        }
        break
      case 'bitcoin':
        // bitcoin is already the primary wallet, make sure the current address is still in the account list
        if (
          bitcoinWallet.accounts.length > 0 &&
          bitcoinWallet.accounts.find(
            (a) => a.address === getGlobalPrimaryAddress()
          ) === undefined
        ) {
          clearGlobalPrimary()
          setGlobalBitcoinAccount(null)
          setBitcoinAccount(null)
        } else if (bitcoinWallet.disconnected) {
          clearGlobalPrimary()
          setBitcoinAccount(null)
        }
        break
    }
  }, [primaryCategory, bitcoinWallet])

  function clearGlobalPrimary() {
    setGlobalPrimaryAccount(null)
    setGlobalPrimaryAddress(null)
    setGlobalPrimaryCategory('none')
  }

  function changeAccount() {
    switch (primaryCategory) {
      case 'evm':
        openWalletConnectModal({ view: 'Account' }).then(() => {})
        break
    }
  }

  return (
    <WalletContext.Provider
      value={{
        connect,
        disconnect,
        changeAccount,
        bitcoinAccount,
        evmAccount,
        primaryAccount,
        primaryCategory,
        primaryAddress
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

export type Wallet = {
  connect: (walletCategory: WalletCategory) => void
  disconnect: () => void
  changeAccount: () => void
  primaryAccount: UseAccountReturnType<Config> | BitcoinAccount | null
  primaryCategory: WalletCategory
  primaryAddress: string | undefined
  bitcoinAccount: BitcoinAccount | null
  evmAccount: UseAccountReturnType<Config> | null
}

export function useWallet(): Wallet {
  const context = useContext(WalletContext)
  if (!context) {
    throw Error(
      'No wallet context found, please make sure the component using this hook is wrapped into WalletProvider'
    )
  }

  const {
    connect,
    disconnect,
    changeAccount,
    bitcoinAccount,
    evmAccount,
    primaryAccount,
    primaryCategory,
    primaryAddress
  } = context

  return {
    connect,
    disconnect,
    changeAccount,
    bitcoinAccount,
    evmAccount,
    primaryAccount,
    primaryCategory,
    primaryAddress
  }
}
