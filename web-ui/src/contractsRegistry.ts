import { ConfigurationApiResponse, Contract } from 'apiClient'

export default class ContractsRegistry {
  private contracts: Map<string, Contract> = new Map()

  static fromConfig(config: ConfigurationApiResponse): ContractsRegistry {
    const contracts = new ContractsRegistry()
    config.chains.forEach((chainConfig) => {
      chainConfig.contracts.forEach((contract) => {
        contracts.contracts.set(`${chainConfig.id}-${contract.name}`, contract)
      })
    })
    return contracts
  }

  private get(chainId: number, name: string): Contract | undefined {
    return this.contracts.get(`${chainId}-${name}`)
  }

  exchange(chainId: number): Contract | undefined {
    return this.get(chainId, 'Exchange')
  }
}
