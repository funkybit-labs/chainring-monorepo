import { getAccount, signTypedData } from '@wagmi/core'
import { chain, wagmiConfig } from 'wagmiConfig'

export type LoadAuthTokenOptions = {
  forceRefresh: boolean
}

let signingPromise: Promise<string> | null = null

export async function loadAuthToken(
  options: LoadAuthTokenOptions = { forceRefresh: false }
): Promise<string> {
  const account = getAccount(wagmiConfig)
  if (account.status === 'connected') {
    const existingToken = localStorage.getItem(`did-${account.address}`)
    if (existingToken && !options.forceRefresh) return existingToken

    if (!signingPromise) {
      signingPromise = signAuthToken(account.address, account.chainId)
    }

    return signingPromise
  }

  return ''
}

async function signAuthToken(
  address: `0x${string}`,
  chainId: number
): Promise<string> {
  try {
    const message = {
      message: `[ChainRing Labs] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.`,
      address: address.toLowerCase(),
      chainId: chainId,
      timestamp: new Date().toISOString()
    }

    const signature = await signTypedData(wagmiConfig, {
      domain: {
        name: 'ChainRing Labs',
        chainId: chain.id
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
      message: message,
      primaryType: 'Sign In'
    })

    const signInMessageBody = base64urlEncode(
      new TextEncoder().encode(JSON.stringify(message))
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
