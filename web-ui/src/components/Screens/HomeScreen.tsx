import { useQuery } from '@tanstack/react-query'
import { apiClient } from 'apiClient'
import { useAccount } from 'wagmi'
import BalancesWidget from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { Header } from 'components/Screens/Header'
import { OrderBookWidget } from 'components/Screens/HomeScreen/OrderBookWidget'
import OrderTicketWidget from 'components/Screens/HomeScreen/OrderTicketWidget'
import { LegacyRef, useEffect, useMemo, useState } from 'react'
import Spinner from 'components/common/Spinner'
import OrdersAndTradesWidget from 'components/Screens/HomeScreen/OrdersAndTradesWidget'
import TradingSymbols from 'tradingSymbols'
import Markets, { Market } from 'markets'
import { WebsocketProvider } from 'contexts/websocket'
import { PricesWidget } from 'components/Screens/HomeScreen/PricesWidget'
import { useMeasure } from 'react-use'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const wallet = useAccount()

  const [selectedMarket, setSelectedMarket] = useState<Market | null>(null)

  const { exchangeContract, markets, symbols, feeRates } = useMemo(() => {
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

    const feeRates = config && config.feeRates

    return {
      exchangeContract,
      markets,
      symbols,
      feeRates
    }
  }, [configQuery.data, wallet.chainId])

  useEffect(() => {
    if (markets !== null && selectedMarket == null) {
      setSelectedMarket(markets.first())
    }
  }, [markets, selectedMarket])

  const [ref, { width }] = useMeasure()

  return (
    <WebsocketProvider wallet={wallet}>
      {markets && feeRates && selectedMarket ? (
        <div className="min-h-screen bg-darkBluishGray10">
          <Header
            markets={markets}
            selectedMarket={selectedMarket}
            onMarketChange={setSelectedMarket}
          />

          <div className="mx-4 flex justify-center py-24">
            <div
              className="min-w-[400px] laptop:max-w-[1800px]"
              ref={ref as LegacyRef<HTMLDivElement>}
            >
              <div className="grid grid-cols-1 gap-4 laptop:grid-cols-3">
                <div className="col-span-1 space-y-4 laptop:col-span-2">
                  <PricesWidget market={selectedMarket} />
                  {symbols && width >= 1100 && (
                    <BalancesWidget
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract?.address}
                      symbols={symbols}
                    />
                  )}
                </div>
                <div className="col-span-1 space-y-4">
                  <OrderTicketWidget
                    market={selectedMarket}
                    walletAddress={wallet.address}
                    exchangeContractAddress={exchangeContract?.address}
                    feeRates={feeRates}
                  />
                  {width >= 1100 && (
                    <OrderBookWidget marketId={selectedMarket.id} />
                  )}
                </div>
                {symbols && width < 1100 && (
                  <div className="col-span-1 space-y-4">
                    <OrderBookWidget marketId={selectedMarket.id} />
                    <BalancesWidget
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract?.address}
                      symbols={symbols}
                    />
                  </div>
                )}
                <div className="col-span-1 space-y-4 laptop:col-span-3">
                  <OrdersAndTradesWidget
                    markets={markets}
                    walletAddress={wallet.address}
                    exchangeContractAddress={exchangeContract?.address}
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
      ) : (
        <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
          <Spinner />
        </div>
      )}
    </WebsocketProvider>
  )
}
