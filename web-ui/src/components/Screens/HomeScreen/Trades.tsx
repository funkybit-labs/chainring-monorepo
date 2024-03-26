import { IncomingWSMessage, Trade } from 'ApiClient'
import { useEffect, useState } from 'react'
import { Widget } from 'components/common/Widget'
import { Websocket, WebsocketEvent } from 'websocket-ts'
import { formatUnits } from 'viem'
import { format } from 'date-fns'

export default function Trades({ ws }: { ws: Websocket }) {
  const [trades, setTrades] = useState<Trade[]>(() => [])

  useEffect(() => {
    const subscribe = () => {
      ws.send(
        JSON.stringify({
          type: 'Subscribe',
          marketId: 'BTC/ETH',
          topic: 'Trades'
        })
      )
    }
    ws.addEventListener(WebsocketEvent.reconnect, subscribe)
    if (ws.readyState == WebSocket.OPEN) {
      subscribe()
    } else {
      ws.addEventListener(WebsocketEvent.open, subscribe)
    }
    const handleMessage = (ws: Websocket, event: MessageEvent) => {
      const message = JSON.parse(event.data) as IncomingWSMessage
      if (message.type == 'Publish' && message.data.type == 'Trades') {
        const incomingTrades = message.data.trades
        setTrades((currentTrades) => {
          return incomingTrades.concat(currentTrades)s
        })
      }
    }
    ws.addEventListener(WebsocketEvent.message, handleMessage)
    return () => {
      ws.removeEventListener(WebsocketEvent.message, handleMessage)
      ws.removeEventListener(WebsocketEvent.reconnect, subscribe)
      ws.removeEventListener(WebsocketEvent.open, subscribe)
      if (ws.readyState == WebSocket.OPEN) {
        ws.send(
          JSON.stringify({
            type: 'Unsubscribe',
            marketId: 'BTC/ETH',
            topic: 'Trades'
          })
        )
      }
    }
  }, [ws])

  return (
    <Widget
      title={'Trades'}
      contents={
        <>
          <div className="h-96 overflow-auto">
            <table className="w-full text-left text-sm">
              {
                <thead>
                  <tr
                    key="header"
                    className="border-b border-b-lightBackground"
                  >
                    <td className="min-w-32">Date</td>
                    <td className="min-w-16 pl-4">Side</td>
                    <td className="min-w-20 pl-4">Amount</td>
                    <td className="min-w-20 pl-4">Market</td>
                    <td className="min-w-20 pl-4">Price</td>
                    <td className="min-w-20 pl-4">Fee</td>
                  </tr>
                </thead>
              }
              <tbody>
                {trades.map((trade) => {
                  return (
                    <tr key={trade.id}>
                      <td className="">
                        {format(trade.timestamp, 'MM/dd HH:mm:ss')}
                      </td>
                      <td className="pl-4">{trade.side}</td>
                      <td className="pl-4">
                        {formatUnits(BigInt(trade.amount), 18)}
                      </td>
                      <td className="pl-4">{trade.marketId}</td>
                      <td className="pl-4">
                        {formatUnits(BigInt(trade.price), 18)}
                      </td>
                      <td className="pl-4">
                        {formatUnits(BigInt(trade.feeAmount), 18)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      }
    />
  )
}
