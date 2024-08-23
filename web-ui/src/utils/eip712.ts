import { Address } from 'viem'

export const addressZero: Address = '0x0000000000000000000000000000000000000000'

export function getDomain(exchangeContractAddress: string, chain: number) {
  return {
    name: 'funkybit',
    chainId: BigInt(chain),
    verifyingContract: exchangeContractAddress as Address,
    version: '0.1.0'
  }
}

export function getWithdrawMessage(
  walletAddress: string,
  tokenAddress: string,
  amount: bigint,
  nonce: bigint
) {
  return {
    sender: walletAddress as Address,
    token: tokenAddress as Address,
    amount: amount,
    nonce: nonce
  }
}

export function generateOrderNonce(): string {
  return crypto.randomUUID().replaceAll('-', '')
}
