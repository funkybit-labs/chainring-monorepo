import { signTypedData } from '@wagmi/core'
import { wagmiConfig } from 'wagmiConfig'
import {
  getGlobalBitcoinAccount,
  getGlobalPrimaryChainId,
  getGlobalPrimaryAddress
} from 'contexts/walletProvider'

export type LoadAuthTokenOptions = {
  forceRefresh: boolean
}

let signingPromise: Promise<string> | null = null

export async function loadAuthToken(
  options: LoadAuthTokenOptions = { forceRefresh: false }
): Promise<string> {
  const primaryAddress = getGlobalPrimaryAddress()
  if (primaryAddress) {
    const existingToken = localStorage.getItem(`did-${primaryAddress}`)
    if (existingToken && !options.forceRefresh) return existingToken

    if (!signingPromise) {
      signingPromise = signAuthToken(primaryAddress, getGlobalPrimaryChainId()!)
    }

    return signingPromise
  }

  return ''
}

async function signAuthToken(
  address: string,
  chainId: number
): Promise<string> {
  try {
    const evmMessage = {
      message: `[funkybit] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.`,
      address: chainId > 0 ? address.toLowerCase() : address,
      chainId: chainId,
      timestamp: new Date().toISOString()
    }

    const signature =
      chainId > 0
        ? await signTypedData(wagmiConfig, {
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
            const bitcoinAccount = getGlobalBitcoinAccount()
            const address = bitcoinAccount!.address
            const messageToSign =
              evmMessage.message +
              `\nAddress: ${address}, Timestamp: ${evmMessage.timestamp}`
            return await bitcoinAccount!.signMessage(address, messageToSign)
          })()

    const signInMessageBody = base64urlEncode(
      new TextEncoder().encode(JSON.stringify(evmMessage))
    )
    const authToken = `${signInMessageBody}.${signature}`
    localStorage.setItem(`did-${address}`, authToken)
    signingPromise = null
    return authToken
  } catch (error) {
    signingPromise = null
    throw error
  }
}

function base64urlEncode(input: Uint8Array): string {
  return Buffer.from(input)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '')
}
