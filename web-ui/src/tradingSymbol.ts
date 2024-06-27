import { AddressType } from 'apiClient'

export default class TradingSymbol {
  name: string
  chainName: string
  description: string
  contractAddress: AddressType | null
  decimals: number
  chainId: number
  faucetSupported: boolean
  withdrawalFee: bigint

  constructor(
    name: string,
    chainName: string,
    description: string,
    contactAddress: AddressType | null,
    decimals: number,
    chainId: number,
    faucetSupported: boolean,
    withdrawalFee: bigint
  ) {
    this.name = name
    this.chainName = chainName
    this.description = description
    this.contractAddress = contactAddress
    this.decimals = decimals
    this.chainId = chainId
    this.faucetSupported = faucetSupported
    this.withdrawalFee = withdrawalFee
  }

  displayName = () => {
    return this.name.replace(new RegExp(':.*', ''), '')
  }
}
