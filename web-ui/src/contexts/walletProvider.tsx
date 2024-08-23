import React, { createContext, useContext, useEffect, useState } from 'react'
import { Config, useAccount, UseAccountReturnType, WagmiProvider } from 'wagmi'
import {
  BitcoinAccount,
  BitcoinProvider,
  useBitcoinWallet
} from 'contexts/bitcoin'
import { wagmiConfig } from 'wagmiConfig'
import Spinner from 'components/common/Spinner'
import { useWeb3Modal } from '@web3modal/wagmi/react'

export type WalletCategory = 'evm' | 'bitcoin'

export const WalletContext = createContext<{
  connect: (category: WalletCategory) => void
  disconnect: () => void
  changeAccount: () => void
  bitcoinAccount: BitcoinAccount | null
  evmAccount: UseAccountReturnType<Config> | null
} | null>(null)

function getGlobal(name: string) {
  // @ts-expect-error linter does not like implicit any type
  const global = window.funkybit || {}
  return global[name] ?? null
}

function setGlobal(
  name: string,
  value: string | UseAccountReturnType | BitcoinAccount | null
) {
  if ('funkybit' in window) {
    // @ts-expect-error linter does not like implicit any type
    window['funkybit'][name] = value
  } else {
    // @ts-expect-error linter does not like implicit any type
    window['funkybit'] = { name: value }
  }
}

export function getEvmAccount(): UseAccountReturnType | null {
  return getGlobal('evmAccount')
}
function setGlobalEvmAccount(account: UseAccountReturnType | null) {
  setGlobal('evmAccount', account)
}

export function getBitcoinAccount(): BitcoinAccount | null {
  return getGlobal('bitcoinAccount')
}
function setGlobalBitcoinAccount(account: BitcoinAccount) {
  setGlobal('bitcoinAccount', account)
}

export function getPrimaryAccount():
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

export function getPrimaryAddress(): string | null {
  return getGlobal('primaryAddress')
}
function setGlobalPrimaryAddress(address: string | null) {
  setGlobal('primaryAddress', address)
}

export function getPrimaryCategory(): WalletCategory | null {
  return getGlobal('primaryCategory')
}
function setGlobalPrimaryCategory(category: WalletCategory) {
  setGlobal('primaryCategory', category)
}

function WalletProviderInternal({ children }: { children: React.ReactNode }) {
  const { open: openWalletConnectModal } = useWeb3Modal()

  const [bitcoinAccount, setBitcoinAccount] = useState<BitcoinAccount | null>(
    null
  )
  const bitcoinWallet = useBitcoinWallet()
  const evmAccount = useAccount()

  useEffect(() => {
    if (evmAccount.isConnected) {
      setGlobalPrimaryAccount(evmAccount)
      setGlobalPrimaryAddress(evmAccount.address ?? null)
      setGlobalEvmAccount(evmAccount)
      setGlobalPrimaryCategory('evm')
    }
  }, [evmAccount])

  function connect(category: WalletCategory) {
    if (category === 'evm') {
      openWalletConnectModal({ view: 'Connect' }).then(() => {})
    } else if (category === 'bitcoin') {
      bitcoinWallet.connect()
    }
  }

  useEffect(() => {
    if (bitcoinWallet.accounts.length > 0) {
      setGlobalPrimaryAccount(bitcoinWallet.accounts[0])
      setGlobalPrimaryAddress(bitcoinWallet.accounts[0].address)
      setGlobalBitcoinAccount(bitcoinWallet.accounts[0])
      setGlobalPrimaryCategory('bitcoin')
    }
    setBitcoinAccount(bitcoinWallet.accounts[0] ?? null)
  }, [bitcoinWallet])

  function disconnect() {}

  function changeAccount() {
    if (evmAccount) {
      openWalletConnectModal({ view: 'Account' }).then(() => {})
    }
  }

  return (
    <WalletContext.Provider
      value={{ connect, disconnect, changeAccount, bitcoinAccount, evmAccount }}
    >
      {children}
    </WalletContext.Provider>
  )
}

export function WalletProvider({ children }: { children: React.ReactNode }) {
  return wagmiConfig ? (
    <WagmiProvider config={wagmiConfig}>
      <BitcoinProvider>
        <WalletProviderInternal>{children}</WalletProviderInternal>
      </BitcoinProvider>
    </WagmiProvider>
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
  primaryCategory: 'evm' | 'bitcoin' | 'none'
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

  const { connect, disconnect, changeAccount, bitcoinAccount, evmAccount } =
    context
  return {
    connect,
    disconnect,
    changeAccount,
    primaryAccount: evmAccount ?? bitcoinAccount,
    primaryCategory: evmAccount?.isConnected
      ? 'evm'
      : bitcoinAccount?.address
        ? 'bitcoin'
        : 'none',
    primaryAddress: evmAccount?.address ?? bitcoinAccount?.address,
    bitcoinAccount,
    evmAccount
  }
}
