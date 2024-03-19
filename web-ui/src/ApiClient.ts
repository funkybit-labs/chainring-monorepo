import { Address } from 'viem'

const apiBaseUrl = import.meta.env.ENV_API_URL

export type DeployedContract = {
  chain: string
  name: string
  address: Address
}

export type ERC20Token = {
  chain: string
  name: string
  symbol: string
  address: Address
  decimals: number
}

export type ConfigurationApiResponse = {
  contracts: DeployedContract[]
  erc20Tokens: ERC20Token[]
}

export async function getConfiguration(): Promise<ConfigurationApiResponse> {
  const response = await fetch(`${apiBaseUrl}/v1/config`)
  return (await response.json()) as ConfigurationApiResponse
}
