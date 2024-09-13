import {
  getGlobalBitcoinAccount,
  disconnectWallet,
  useWallets,
  getGlobalPrimaryWallet
} from 'contexts/walletProvider'
import { bitcoinSignMessage, evmSignTypedData } from 'utils/signingUtils'
import { base64urlEncode } from 'utils'
import { createContext, useContext, useEffect, useState } from 'react'

export type LoadAuthTokenOptions = {
  forceRefresh: boolean
}

type AuthEvent = {
  _kind: 'authenticated'
}

type EventHandler = (event: AuthEvent) => void
const subscriptions: EventHandler[] = []

function subscribe(eventHandler: EventHandler) {
  subscriptions.push(eventHandler)
}

function unsubscribe(eventHandler: EventHandler) {
  const idx = subscriptions.indexOf(eventHandler)
  if (idx != -1) {
    subscriptions.splice(idx, 1)
  }
}

const Auth = {
  subscribe,
  unsubscribe
}

let signingPromise: Promise<string> | null = null

export async function loadAuthToken(
  options: LoadAuthTokenOptions = { forceRefresh: false }
): Promise<string> {
  const primaryWallet = getGlobalPrimaryWallet()
  // console.log(primaryWallet)

  if (primaryWallet) {
    const existingToken = localStorage.getItem(`did-${primaryWallet.address}`)
    // console.log(!!existingToken)
    if (existingToken && !options.forceRefresh) return existingToken

    if (!signingPromise) {
      signingPromise = signAuthToken(
        primaryWallet.address,
        primaryWallet.chainId
      )
        .then((token) => {
          signingPromise = null
          if (token == null) {
            // user has rejected signing the token, disconnect
            disconnectWallet(primaryWallet.networkType)
            return ''
          } else {
            return token
          }
        })
        .catch((error) => {
          signingPromise = null
          throw error
        })
    }

    return signingPromise
  }

  return ''
}

export async function signAuthToken(
  address: string,
  chainId: number
): Promise<string | null> {
  const evmMessage = {
    message: `[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.`,
    address: chainId > 0 ? address.toLowerCase() : address,
    chainId: chainId,
    timestamp: new Date().toISOString()
  }

  const signature =
    chainId > 0
      ? await evmSignTypedData({
          domain: {
            name: 'funkybit',
            chainId: chainId
          },
          types: {
            EIP712Domain: [
              { name: 'name', type: 'string' },
              { name: 'chainId', type: 'uint32' }
            ],
            'Sign In': [
              { name: 'message', type: 'string' },
              { name: 'address', type: 'string' },
              { name: 'chainId', type: 'uint32' },
              { name: 'timestamp', type: 'string' }
            ]
          },
          message: evmMessage,
          primaryType: 'Sign In'
        })
      : await (async () => {
          const address = getGlobalBitcoinAccount()!.address
          return await bitcoinSignMessage(
            address,
            evmMessage.message +
              `\nAddress: ${address}, Timestamp: ${evmMessage.timestamp}`
          )
        })()

  if (signature == null) {
    return null
  }

  const signInMessageBody = base64urlEncode(
    new TextEncoder().encode(JSON.stringify(evmMessage))
  )
  const authToken = `${signInMessageBody}.${signature}`
  localStorage.setItem(`did-${address}`, authToken)
  subscriptions.forEach((eventHandler) => {
    eventHandler({ _kind: 'authenticated' })
  })
  return authToken
}

function primaryAuthTokenExists(): boolean {
  const primaryWallet = getGlobalPrimaryWallet()
  return (
    primaryWallet !== null &&
    localStorage.getItem(`did-${primaryWallet.address}`) !== null
  )
}

export const AuthContext = createContext<{
  isAuthenticated: boolean
} | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const wallets = useWallets()

  const [isAuthenticated, setIsAuthenticated] = useState(
    primaryAuthTokenExists()
  )

  useEffect(() => {
    if (wallets.connected.length == 0) {
      setIsAuthenticated(false)
    } else {
      setIsAuthenticated(primaryAuthTokenExists())
    }
  }, [wallets.connected])

  // useEffect(() => {
  //   // console.log(wallets.primary)
  //   if (wallets.primary) {
  //     // trigger auth-token signing in case
  //     // we don't have a token for new primary wallet yet
  //     loadAuthToken()
  //   }
  // }, [wallets.primary])

  useEffect(() => {
    Auth.subscribe(handleAuthEvent)
    return () => {
      Auth.unsubscribe(handleAuthEvent)
    }
  }, [])

  function handleAuthEvent(event: AuthEvent) {
    switch (event._kind) {
      case 'authenticated':
        setIsAuthenticated(true)
        break
    }
  }

  return (
    <AuthContext.Provider value={{ isAuthenticated }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw Error(
      'No auth context found, please make sure the component using this hook is wrapped into AuthProvider'
    )
  }

  const { isAuthenticated } = context
  return { isAuthenticated }
}
