import { Address } from 'viem'

const apiBaseUrl = import.meta.env.ENV_API_URL

export type DeployedContract = {
  name: string
  address: Address
}

export type ERC20Token = {
  name: string
  symbol: string
  address: Address
  decimals: number
}

export type NativeToken = {
  name: string
  symbol: string
  decimals: number
}

export type Token = NativeToken | ERC20Token

export type Chain = {
  id: number
  contracts: DeployedContract[]
  erc20Tokens: ERC20Token[]
  nativeToken: NativeToken
}

export type ConfigurationApiResponse = {
  chains: Chain[]
}

export async function getConfiguration(): Promise<ConfigurationApiResponse> {
  const response = await fetch(`${apiBaseUrl}/v1/config`)
  return (await response.json()) as ConfigurationApiResponse
}
