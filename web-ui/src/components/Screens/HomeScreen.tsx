import { useQuery } from '@tanstack/react-query'
import { apiClient } from 'apiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'
import { Header } from 'components/Screens/Header'
import { OrderBook } from 'components/Screens/HomeScreen/OrderBook'
import SubmitOrder from 'components/Screens/HomeScreen/SubmitOrder'
import TradeHistory from 'components/Screens/HomeScreen/TradeHistory'
import { Prices } from 'components/Screens/HomeScreen/Prices'
import { useEffect, useMemo, useState } from 'react'
import Spinner from 'components/common/Spinner'
import Orders from 'components/Screens/HomeScreen/Orders'
import TradingSymbols from 'tradingSymbols'
import Markets, { Market } from 'markets'
import { WebsocketProvider } from 'contexts/websocket'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const wallet = useAccount()

  const [selectedMarket, setSelectedMarket] = useState<Market | null>(null)

  const { exchangeContract, markets, symbols } = useMemo(() => {
    const config = configQuery.data
    const chainConfig = config?.chains.find(
      (chain) => chain.id === (wallet.chainId || config.chains[0]?.id)
    )

    const exchangeContract = chainConfig?.contracts?.find(
      (c) => c.name == 'Exchange'
    )

    const symbols = chainConfig ? new TradingSymbols(chainConfig.symbols) : null
    const markets =
      config && symbols ? new Markets(config.markets, symbols) : null

    return {
      exchangeContract,
      markets,
      symbols
    }
  }, [configQuery.data, wallet.chainId])

  useEffect(() => {
    if (markets !== null && selectedMarket == null) {
      setSelectedMarket(markets.first())
    }
  }, [markets, selectedMarket])

  return (
    <WebsocketProvider wallet={wallet}>
      {markets && selectedMarket ? (
        <div className="h-screen bg-gradient-to-b from-lightBackground to-darkBackground">
          <Header
            markets={markets}
            selectedMarket={selectedMarket}
            onMarketChange={setSelectedMarket}
          />

          <div className="flex h-screen w-screen flex-col gap-4 overflow-y-scroll px-4 py-24">
            <div className="flex flex-wrap gap-4">
              <OrderBook marketId={selectedMarket.id} />
              <Prices marketId={selectedMarket.id} />
              {wallet.address && (
                <>
                  {exchangeContract && (
                    <SubmitOrder
                      market={selectedMarket}
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract.address}
                      baseSymbol={selectedMarket.baseSymbol}
                      quoteSymbol={selectedMarket.quoteSymbol}
                    />
                  )}
                  <Orders markets={markets} />
                  <TradeHistory markets={markets} />
                  {symbols && exchangeContract && (
                    <Balances
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract.address}
                      symbols={symbols}
                    />
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      ) : (
        <div className="flex h-screen items-center justify-center bg-gradient-to-b from-lightBackground to-darkBackground">
          <Spinner />
        </div>
      )}
    </WebsocketProvider>
  )
}
