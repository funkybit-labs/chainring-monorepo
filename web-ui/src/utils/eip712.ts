import { Address } from 'viem'

export const addressZero: Address = '0x0000000000000000000000000000000000000000'

export function getDomain(exchangeContractAddress: Address, chain: number) {
  return {
    name: 'ChainRing Labs',
    chainId: BigInt(chain),
    verifyingContract: exchangeContractAddress,
    version: '0.0.1'
  }
}

export function getERC20WithdrawMessage(
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

export function getNativeWithdrawMessage(
  walletAddress: Address,
  amount: bigint,
  nonce: bigint
) {
  return {
    sender: walletAddress,
    amount: amount,
    nonce: nonce
  }
}
