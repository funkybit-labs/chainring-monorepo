import SatsConnect, { RpcErrorCode } from 'sats-connect'

export const bitcoinEnabled = import.meta.env.ENV_ENABLE_BITCOIN

type ConnectedEvent = {
  _kind: 'connected'
  accounts: BitcoinAccount[]
}
type DisconnectedEvent = {
  _kind: 'disconnected'
}
export type BitcoinConnectorEvent = ConnectedEvent | DisconnectedEvent

type EventHandler = (event: BitcoinConnectorEvent) => void

export type BitcoinAccount = {
  address: string
  publicKey: string
  purpose: 'payment' | 'ordinals' | 'stacks'
  addressType: 'p2tr' | 'p2wpkh' | 'p2sh' | 'stacks' | 'p2pkh' | 'p2wsh'
  walletType: 'software' | 'ledger'
}

const subscriptions: EventHandler[] = []

async function connect() {
  const data = await SatsConnect.request('getAccounts', {
    message: 'Connect your bitcoin wallet to get funky',
    // @ts-expect-error linter thinks these are not a valid AddressPurpose, but they are
    purposes: ['payment', 'ordinals']
  })
  if (data.status === 'success') {
    const newAccounts = data.result
    if (newAccounts.length > 0) {
      SatsConnect.addListener('accountChange', () => {
        connect()
      })
    }
    subscriptions.forEach((eventHandler) => {
      eventHandler({ _kind: 'connected', accounts: newAccounts })
    })
  } else {
    if (data.error.code !== RpcErrorCode.USER_REJECTION) {
      throw Error(`Unable to connect to wallet: ${data.error}`)
    }
  }
}

function subscribe(handler: EventHandler) {
  subscriptions.push(handler)
}

function unsubscribe(handler: EventHandler) {
  const idx = subscriptions.indexOf(handler)
  if (idx != -1) {
    subscriptions.splice(idx, 1)
  }
}

function disconnect() {
  SatsConnect.request('wallet_renouncePermissions', undefined).finally(() => {
    SatsConnect.disconnect().finally(() => {
      subscriptions.forEach((eventHandler) => {
        eventHandler({ _kind: 'disconnected' })
      })
    })
  })
}

export const BitcoinConnector = {
  connect: connect,
  disconnect: disconnect,
  subscribe: subscribe,
  unsubscribe: unsubscribe
}
