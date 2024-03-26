import { OutgoingWSMessage, Trade } from 'ApiClient'
import { useEffect, useState } from 'react'
import { Widget } from 'components/common/Widget'
import { Websocket, WebsocketEvent } from 'websocket-ts'
import { concat, formatUnits } from 'viem'
import { Button } from 'components/common/Button'

export default function Trades({ ws }: { ws: Websocket }) {
  const [trades, setTrades] = useState<Trade[]>(() => [])

  useEffect(() => {
    const subscribe = () => {
      ws.send(JSON.stringify({ type: 'Subscribe', marketId: 'BTC/ETH' }))
    }
    ws.addEventListener(WebsocketEvent.reconnect, subscribe)
    if (ws.readyState == WebSocket.OPEN) {
      subscribe()
    } else {
      ws.addEventListener(WebsocketEvent.open, subscribe)
    }
    const handleMessage = (ws: Websocket, event: MessageEvent) => {
      const message = JSON.parse(event.data) as OutgoingWSMessage
      if (message.type == 'Publish' && message.data.type == 'Trades') {
        setTrades(trades.concat(message.data.trades))
      }
    }
    ws.addEventListener(WebsocketEvent.message, handleMessage)
    return () => {
      ws.removeEventListener(WebsocketEvent.message, handleMessage)
      ws.removeEventListener(WebsocketEvent.reconnect, subscribe)
      ws.removeEventListener(WebsocketEvent.open, subscribe)
      if (ws.readyState == WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'Unsubscribe', marketId: 'BTC/ETH' }))
      }
    }
  }, [ws])

  return (
    <Widget
      title={'Trades'}
      contents={
        <>
          <table className="w-full text-left text-sm p-10">
            {
              <th>
                <td className="p-2">Date</td>
                <td className="p-2">Side</td>
                <td className="p-2">Amount</td>
                <td className="p-2">Market</td>
                <td className="p-2">Price</td>
                <td className="p-2">Fee</td>
              </th>
            }
            <tbody>
              {trades.map((trade) => {
                return (
                  <tr>
                    <td className="min-w-12 pr-2">{trade.timestamp}</td>
                    <td className="min-w-12 pr-2">{trade.side}</td>
                    <td className="min-w-12 pr-2">{trade.amount}</td>
                    <td className="min-w-12 pr-2">{trade.marketId}</td>
                    <td className="min-w-12 pr-2">{trade.price}</td>
                    <td className="min-w-12 pr-2">{trade.feeAmount}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </>
      }
    />
  )
}
