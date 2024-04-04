import { useQuery } from '@tanstack/react-query'
import { apiBaseUrl, apiClient } from 'ApiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'
import { Header } from 'components/Screens/Header'
import { OrderBook } from 'components/Screens/HomeScreen/OrderBook'
import SubmitOrder from 'components/Screens/HomeScreen/SubmitOrder'
import TradeHistory from 'components/Screens/HomeScreen/TradeHistory'
import { ExponentialBackoff, Websocket, WebsocketBuilder } from 'websocket-ts'
import { Prices } from 'components/Screens/HomeScreen/Prices'
import { useEffect, useMemo, useState } from 'react'
import Spinner from 'components/common/Spinner'
import { loadAuthToken } from 'Auth'
import Orders from 'components/Screens/HomeScreen/Orders'
import TradingSymbols from 'tradingSymbols'
import Markets, { Market } from 'markets'

const websocketUrl =
  apiBaseUrl.replace('http:', 'ws:').replace('https:', 'wss:') + '/connect'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const wallet = useAccount()
  const walletAddress = wallet.address

  const [ws, setWs] = useState<Websocket | null>(null)
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
    let reconnecting = false

    const initWebSocket = async (refreshAuth: boolean = false) => {
      if (reconnecting) return
      reconnecting = true

      const authQuery =
        walletAddress && wallet.status == 'connected'
          ? `?auth=${await loadAuthToken(refreshAuth)}`
          : ''

      setWs((prevWs) => {
        if (prevWs != null) {
          prevWs.close()
        }
        return new WebsocketBuilder(websocketUrl + authQuery)
          .withBackoff(new ExponentialBackoff(1000, 4))
          .onClose((ws, event) => {
            if (event.code == 3000) initWebSocket(true)
          })
          .build()
      })
      reconnecting = false
    }

    initWebSocket()
  }, [walletAddress, wallet.status])

  useEffect(() => {
    if (markets !== null && selectedMarket == null) {
      setSelectedMarket(markets.first())
    }
  }, [markets, selectedMarket])

  if (markets && selectedMarket && ws) {
    return (
      <div className="h-screen bg-gradient-to-b from-lightBackground to-darkBackground">
        <Header
          markets={markets}
          selectedMarket={selectedMarket}
          onMarketChange={setSelectedMarket}
        />

        <div className="flex h-screen w-screen flex-col gap-4 overflow-y-scroll px-4 py-24">
          <div className="flex flex-wrap gap-4">
            <OrderBook ws={ws} marketId={selectedMarket.id} />
            <Prices ws={ws} marketId={selectedMarket.id} />
            {walletAddress && (
              <SubmitOrder
                baseSymbol={selectedMarket.baseSymbol}
                quoteSymbol={selectedMarket.quoteSymbol}
              />
            )}
            {walletAddress && <Orders ws={ws} markets={markets} />}
            {walletAddress && <TradeHistory ws={ws} />}
            {walletAddress && symbols && exchangeContract && (
              <Balances
                walletAddress={walletAddress}
                exchangeContractAddress={exchangeContract.address}
                symbols={symbols}
              />
            )}
          </div>
        </div>
      </div>
    )
  } else {
    return (
      <div className="flex h-screen items-center justify-center bg-gradient-to-b from-lightBackground to-darkBackground">
        <Spinner />
      </div>
    )
  }
}
