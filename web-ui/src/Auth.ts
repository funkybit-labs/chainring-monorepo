import { getAccount, signMessage } from '@wagmi/core'
import { wagmiConfig } from 'wagmiConfig'
import { v4 as uuidv4 } from 'uuid'

export async function loadOrIssueDidToken(): Promise<string> {
  const account = getAccount(wagmiConfig)
  if (account.status === 'connected') {
    const existingToken = localStorage.getItem(`did-${account.address}`)
    if (existingToken) return existingToken

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

function base64urlEncode(input: Uint8Array): string {
  return Buffer.from(input)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '')
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
