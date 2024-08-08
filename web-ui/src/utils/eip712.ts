import { Address } from 'viem'

export const addressZero: Address = '0x0000000000000000000000000000000000000000'

export function getDomain(exchangeContractAddress: Address, chain: number) {
  return {
    name: 'funkybit',
    chainId: BigInt(chain),
    verifyingContract: exchangeContractAddress,
    version: '0.1.0'
  }
}

export function getWithdrawMessage(
  walletAddress: Address,
  tokenAddress: Address,
  amount: bigint,
  nonce: bigint
) {
  return {
    sender: walletAddress,
    token: tokenAddress,
    amount: amount,
    nonce: nonce
  }
}

export function generateOrderNonce(): string {
  return crypto.randomUUID().replaceAll('-', '')
}
