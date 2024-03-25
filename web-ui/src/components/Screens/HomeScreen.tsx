import { useQuery } from '@tanstack/react-query'
import { apiClient, apiBaseUrl, Market } from 'ApiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'
import { Header } from 'components/Screens/Header'
import { OrderBook } from 'components/Screens/HomeScreen/OrderBook'
import Trade from 'components/Screens/HomeScreen/Trade'
import { ExponentialBackoff, WebsocketBuilder } from 'websocket-ts'
import { Prices } from 'components/Screens/HomeScreen/Prices'
import { useEffect, useMemo, useState } from 'react'

const websocketUrl =
  apiBaseUrl.replace('http:', 'ws:').replace('https:', 'wss:') + '/connect'

const ws = new WebsocketBuilder(websocketUrl)
  .withBackoff(new ExponentialBackoff(1000, 4))
  .build()

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
  const symbols = chainConfig?.symbols

  const [selectedMarket, setSelectedMarket] = useState<Market | null>(null)

  const markets = useMemo(() => {
    return configQuery.data?.markets || []
  }, [configQuery.data])

  useEffect(() => {
    if (markets.length > 0 && selectedMarket == null) {
      setSelectedMarket(markets[0])
    }
  }, [markets, selectedMarket])

  return (
    <div className="h-screen bg-gradient-to-b from-lightBackground to-darkBackground">
      <Header
        markets={markets}
        selectedMarket={selectedMarket}
        onMarketChange={setSelectedMarket}
      />

      <div className="flex h-screen w-screen flex-col gap-4 px-4 pt-24">
        <div className="flex gap-4">
          {selectedMarket && (
            <div className="flex flex-col">
              <OrderBook ws={ws} marketId={selectedMarket.id} />
            </div>
          )}
          <div className="flex flex-col">
            <Prices ws={ws} />
          </div>
          <div className="flex flex-col">
            {walletAddress && exchangeContract && symbols && (
              <>
                <Trade baseSymbol={'ETH'} quoteSymbol={'USDC'} />
              </>
            )}
          </div>
        </div>
        <div className="flex gap-4">
          <div className="flex flex-col">
            {walletAddress && exchangeContract && symbols && (
              <>
                <Balances
                  walletAddress={walletAddress}
                  exchangeContractAddress={exchangeContract.address}
                  symbols={symbols}
                />
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
