import { AddressType } from 'apiClient'

export default class TradingSymbol {
  name: string
  description: string
  contractAddress: AddressType | null
  decimals: number
  chainId: number

  constructor(
    name: string,
    description: string,
    contactAddress: AddressType | null,
    decimals: number,
    chainId: number
  ) {
    this.name = name
    this.description = description
    this.contractAddress = contactAddress
    this.decimals = decimals
    this.chainId = chainId
  }
}
