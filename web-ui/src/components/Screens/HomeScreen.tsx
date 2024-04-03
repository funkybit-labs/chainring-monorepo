import { useQuery } from '@tanstack/react-query'
import { apiBaseUrl, apiClient, Market } from 'ApiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'
import { Header } from 'components/Screens/Header'
import { OrderBook } from 'components/Screens/HomeScreen/OrderBook'
import Order from 'components/Screens/HomeScreen/Order'
import Trades from 'components/Screens/HomeScreen/Trades'
import { ExponentialBackoff, Websocket, WebsocketBuilder } from 'websocket-ts'
import { Prices } from 'components/Screens/HomeScreen/Prices'
import { useEffect, useMemo, useRef, useState } from 'react'
import Spinner from 'components/common/Spinner'
import { loadOrIssueDidToken } from 'Auth'

const websocketUrl =
  apiBaseUrl.replace('http:', 'ws:').replace('https:', 'wss:') + '/connect'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const wallet = useAccount()
  const walletAddress = wallet.address

  const wsRef = useRef<Websocket | null>(null)
  const [wsConnectionId, setWsConnectionId] = useState(0)

  const config = configQuery.data
  const chainConfig = config?.chains.find((chain) => chain.id == wallet.chainId)

  const exchangeContract = chainConfig?.contracts?.find(
    (c) => c.name == 'Exchange'
  )
  const symbols = chainConfig?.symbols

  const [selectedMarket, setSelectedMarket] = useState<Market | null>(null)

  const markets = useMemo(() => {
    return config?.markets || []
  }, [config?.markets])

  useEffect(() => {
    const initWebSocket = async () => {
      if (wsRef.current) {
        wsRef.current.close()
      }

      const authQuery =
        walletAddress && wallet.status == 'connected'
          ? `?auth=${await loadOrIssueDidToken()}`
          : ''
      const websocket = new WebsocketBuilder(websocketUrl + authQuery)
        .withBackoff(new ExponentialBackoff(1000, 4))
        .build()

      wsRef.current = websocket
      setWsConnectionId((id) => id + 1)
    }

    initWebSocket()

    return () => {
      if (wsRef.current) {
        wsRef.current.close()
      }
    }
  }, [walletAddress, wallet.status])

  useEffect(() => {
    if (markets.length > 0 && selectedMarket == null) {
      setSelectedMarket(markets[0])
    }
  }, [markets, selectedMarket])

  if (markets && selectedMarket && wsRef.current) {
    return (
      <div className="h-screen bg-gradient-to-b from-lightBackground to-darkBackground">
        <Header
          markets={markets}
          selectedMarket={selectedMarket}
          onMarketChange={setSelectedMarket}
        />

        <div className="flex h-screen w-screen flex-col gap-4 px-4 pt-24">
          <div className="flex gap-4">
            <div className="flex flex-col">
              <OrderBook
                ws={{ socket: wsRef.current, connectionId: wsConnectionId }}
                marketId={selectedMarket.id}
              />
            </div>
            <div className="flex flex-col">
              <Prices
                ws={{ socket: wsRef.current, connectionId: wsConnectionId }}
                marketId={selectedMarket.id}
              />
            </div>
            {walletAddress && (
              <div className="flex flex-col">
                <Order
                  baseSymbol={selectedMarket.baseSymbol}
                  quoteSymbol={selectedMarket.quoteSymbol}
                />
              </div>
            )}
          </div>
          <div className="flex gap-4">
            <div className="flex flex-col">
              {walletAddress && (
                <Trades
                  ws={{ socket: wsRef.current, connectionId: wsConnectionId }}
                />
              )}
            </div>
            <div className="flex flex-col">
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
