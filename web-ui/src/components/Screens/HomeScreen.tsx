import { useQuery } from '@tanstack/react-query'
import { apiClient, apiBaseUrl } from 'ApiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'
import { Header } from 'components/Screens/Header'
import { OrderBook } from 'components/Screens/HomeScreen/OrderBook'
import Trade from 'components/Screens/HomeScreen/Trade'
import { ExponentialBackoff, WebsocketBuilder } from 'websocket-ts'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const wallet = useAccount()
  const walletAddress = wallet.address

  const chainConfig = configQuery.data?.chains.find(
    (chain) => chain.id == wallet.chainId
  )
  const exchangeContract = chainConfig?.contracts?.find(
    (c) => c.name == 'Exchange'
  )

  const nativeToken = chainConfig?.nativeToken
  const erc20Tokens = chainConfig?.erc20Tokens
  const ws = new WebsocketBuilder(
    apiBaseUrl.replace('http:', 'ws:').replace('https:', 'wss:') + '/connect'
  )
    .withBackoff(new ExponentialBackoff(1000, 4))
    .build()

  return (
    <div className="h-screen bg-gradient-to-b from-lightBackground to-darkBackground">
      <Header />
      <div className="flex h-screen w-screen flex-col">
        <div className="flex gap-4 px-4 pt-24">
          <div className="flex flex-col">
            <OrderBook ws={ws} />
          </div>
          <div className="flex flex-col">
            {walletAddress &&
              exchangeContract &&
              erc20Tokens &&
              nativeToken && (
                <>
                  <Trade baseSymbol={'BTC'} quoteSymbol={'ETH'} />
                </>
              )}
          </div>
        </div>
        <div className="flex px-4 pt-24">
          <div className="flex flex-col items-center gap-4">
            {walletAddress &&
              exchangeContract &&
              erc20Tokens &&
              nativeToken && (
                <>
                  <Balances
                    walletAddress={walletAddress}
                    exchangeContractAddress={exchangeContract.address}
                    nativeToken={nativeToken}
                    erc20Tokens={erc20Tokens}
                  />
                </>
              )}
          </div>
        </div>
      </div>
    </div>
  )
}
