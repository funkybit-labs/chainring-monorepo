import { createContext, useContext, useEffect, useState } from 'react'
import SatsConnect, { RpcErrorCode } from 'sats-connect'

export const BitcoinContext = createContext<{
  connect: () => void
  disconnect: () => void
  accounts: BitcoinAccount[]
  disconnected: boolean
} | null>(null)

export type BitcoinAccount = {
  address: string
  publicKey: string
  purpose: 'payment' | 'ordinals' | 'stacks'
  addressType: 'p2tr' | 'p2wpkh' | 'p2sh' | 'stacks' | 'p2pkh' | 'p2wsh'
  walletType: 'software' | 'ledger'
  signMessage: (address: string, message: string) => Promise<string>
}

export function BitcoinProvider({ children }: { children: React.ReactNode }) {
  const [accounts, setAccounts] = useState<BitcoinAccount[]>([])
  const [disconnected, setDisconnected] = useState(false)

  useEffect(() => {
    if (accounts.length > 0) {
      return SatsConnect.addListener('accountChange', () => {
        console.log('change')
        connect()
      })
    }
  }, [accounts])

  async function connect() {
    const data = await SatsConnect.request('getAccounts', {
      message: 'Connect your bitcoin wallet to get funky',
      // @ts-expect-error linter thinks these are not a valid AddressPurpose, but they are
      purposes: ['payment', 'ordinals']
    })
    if (data.status === 'success') {
      setDisconnected(false)
      setAccounts(
        data.result.map((a) => {
          return {
            ...a,
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
        })
      )
    } else {
      setAccounts([])
      if (data.error.code !== RpcErrorCode.USER_REJECTION) {
        throw Error(`Unable to connect to wallet: ${data.error}`)
      }
    }
  }

  function disconnect() {
    setDisconnected(true)
    setAccounts([])
    console.log('a')
    SatsConnect.request('wallet_renouncePermissions', undefined).finally(() => {
      SatsConnect.disconnect()
    })
  }

  return (
    <BitcoinContext.Provider
      value={{ connect, disconnect, accounts, disconnected }}
    >
      {children}
    </BitcoinContext.Provider>
  )
}

export function useBitcoinWallet() {
  const context = useContext(BitcoinContext)
  if (!context) {
    throw Error(
      'No bitcoin context found, please make sure the component using this hook is wrapped into BitcoinProvider'
    )
  }

  const { connect, disconnect, accounts, disconnected } = context
  return { connect, disconnect, accounts, disconnected }
}
