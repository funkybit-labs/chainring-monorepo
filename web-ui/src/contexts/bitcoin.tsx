import { createContext, useContext, useEffect, useRef, useState } from 'react'
import SatsConnect, { RpcErrorCode } from 'sats-connect'

export const bitcoinEnabled = import.meta.env.ENV_ENABLE_BITCOIN

type ConnectedEvent = {
  _kind: 'connected'
  accounts: BitcoinAccount[]
}
type DisconnectedEvent = {
  _kind: 'disconnected'
}
type Event = ConnectedEvent | DisconnectedEvent

type EventHandler = (event: Event) => void

export const BitcoinContext = createContext<{
  connect: () => void
  disconnect: () => void
  subscribe: (handler: EventHandler) => void
  unsubscribe: (handler: EventHandler) => void
  accounts: BitcoinAccount[]
} | null>(null)

export type BitcoinAccount = {
  address: string
  publicKey: string
  purpose: 'payment' | 'ordinals' | 'stacks'
  addressType: 'p2tr' | 'p2wpkh' | 'p2sh' | 'stacks' | 'p2pkh' | 'p2wsh'
  walletType: 'software' | 'ledger'
}

export async function bitcoinSignMessage(
  address: string,
  message: string
): Promise<string | null> {
  const result = await SatsConnect.request('signMessage', {
    address,
    message
  })
  if (result.status === 'success') {
    return result.result.signature
  } else if (result.error.code === RpcErrorCode.USER_REJECTION) {
    return null
  } else {
    console.log(result.error)
    throw Error('Failed to sign message with bitcoin wallet')
  }
}

export function BitcoinProvider({ children }: { children: React.ReactNode }) {
  const [accounts, setAccounts] = useState<BitcoinAccount[]>([])

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
      const newAccounts = data.result
      setAccounts(newAccounts)
      subscriptions.current.forEach((eventHandler) => {
        eventHandler({ _kind: 'connected', accounts: newAccounts })
      })
    } else {
      setAccounts([])
      if (data.error.code !== RpcErrorCode.USER_REJECTION) {
        throw Error(`Unable to connect to wallet: ${data.error}`)
      }
    }
  }

  const subscriptions = useRef<EventHandler[]>([])

  function subscribe(handler: EventHandler) {
    subscriptions.current.push(handler)
  }

  function unsubscribe(handler: EventHandler) {
    const idx = subscriptions.current.indexOf(handler)
    if (idx != -1) {
      subscriptions.current.splice(idx, 1)
    }
  }

  function disconnect() {
    setAccounts([])
    SatsConnect.request('wallet_renouncePermissions', undefined).finally(() => {
      SatsConnect.disconnect().finally(() => {
        subscriptions.current.forEach((eventHandler) => {
          eventHandler({ _kind: 'disconnected' })
        })
      })
    })
  }

  return (
    <BitcoinContext.Provider
      value={{ connect, disconnect, accounts, subscribe, unsubscribe }}
    >
      {children}
    </BitcoinContext.Provider>
  )
}

export function useBitcoinWallet({
  eventHandler
}: {
  eventHandler: EventHandler
}) {
  const context = useContext(BitcoinContext)
  if (!context) {
    throw Error(
      'No bitcoin context found, please make sure the component using this hook is wrapped into BitcoinProvider'
    )
  }

  const { connect, disconnect, subscribe, unsubscribe } = context
  useEffect(() => {
    subscribe(eventHandler)

    return () => {
      unsubscribe(eventHandler)
    }
  }, [subscribe, unsubscribe, eventHandler])
  return { connect, disconnect }
}
