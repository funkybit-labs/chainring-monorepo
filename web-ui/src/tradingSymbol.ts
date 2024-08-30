import { AddressType, NetworkType } from 'apiClient'

export default class TradingSymbol {
  name: string
  chainName: string
  description: string
  contractAddress: AddressType | null
  decimals: number
  chainId: number
  networkType: NetworkType
  faucetSupported: boolean
  withdrawalFee: bigint
  iconUrl: string

  constructor(
    name: string,
    chainName: string,
    description: string,
    contactAddress: AddressType | null,
    decimals: number,
    chainId: number,
    networkType: NetworkType,
    faucetSupported: boolean,
    withdrawalFee: bigint,
    iconUrl: string
  ) {
    this.name = name
    this.chainName = chainName
    this.description = description
    this.contractAddress = contactAddress
    this.decimals = decimals
    this.chainId = chainId
    this.networkType = networkType
    this.faucetSupported = faucetSupported
    this.withdrawalFee = withdrawalFee
    this.iconUrl = iconUrl
  }

  displayName = () => {
    return this.name.replace(new RegExp(':.*', ''), '')
  }
}
