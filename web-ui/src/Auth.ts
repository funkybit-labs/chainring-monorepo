import { getAccount, signMessage } from '@wagmi/core'
import { wagmiConfig } from 'wagmiConfig'
import { v4 as uuidv4 } from 'uuid'

export async function loadOrIssueDidToken(): Promise<string> {
  const account = getAccount(wagmiConfig)
  if (account.status === 'connected') {
    const existingToken = localStorage.getItem(`did-${account.address}`)
    if (existingToken && isValid(existingToken)) return existingToken

    const tokenBody = createDidToken(account.address)
    const tokenSignature = await signMessage(wagmiConfig, {
      message: tokenBody
    })

    const didToken = `${tokenBody}.${tokenSignature}`
    localStorage.setItem(`did-${account.address}`, didToken)

    return didToken
  }

  return ''
}

export function didTokenIsValid(): boolean {
  const account = getAccount(wagmiConfig)
  if (account.status === 'connected') {
    const existingToken = localStorage.getItem(`did-${account.address}`)
    return existingToken != null && isValid(existingToken)
  }

  return false
}

function base64urlEncode(input: Uint8Array): string {
  return Buffer.from(input)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '')
}

function base64urlDecode(input: string): string {
  let base64 = input.replace(/-/g, '+').replace(/_/g, '/')
  while (base64.length % 4) {
    base64 += '='
  }
  return Buffer.from(base64, 'base64').toString()
}

function createDidToken(address: string) {
  const issuedAt = Math.floor(Date.now() / 1000)
  const header = JSON.stringify({
    alg: 'ES256K',
    typ: 'JWT'
  })
  const claims = JSON.stringify({
    iat: issuedAt,
    ext: issuedAt + 24 * 60 * 60,
    iss: `did:ethr:${address}`,
    aud: 'chainring',
    tid: uuidv4()
  })

  const encodedHeader = base64urlEncode(new TextEncoder().encode(header))
  const encodedClaims = base64urlEncode(new TextEncoder().encode(claims))
  return `${encodedHeader}.${encodedClaims}`
}

function isValid(token: string): boolean {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) {
      return false
    }

    const payload = parts[1]
    const decodedPayload = base64urlDecode(payload)
    const claims = JSON.parse(decodedPayload)

    const currentTime = Math.floor(Date.now() / 1000)
    return currentTime < claims.ext
  } catch (error) {
    return false
  }
}
