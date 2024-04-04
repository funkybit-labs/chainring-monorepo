import { IncomingWSMessage, Trade, TradesSchema } from 'ApiClient'
import { useEffect, useState } from 'react'
import { Widget } from 'components/common/Widget'
import { Websocket, WebsocketEvent } from 'websocket-ts'
import { formatUnits } from 'viem'
import { format } from 'date-fns'

export default function TradeHistory({ ws }: { ws: Websocket }) {
  const [trades, setTrades] = useState<Trade[]>(() => [])

  useEffect(() => {
    const subscribe = () => {
      ws.send(
        JSON.stringify({
          type: 'Subscribe',
          topic: { type: 'Trades' }
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
        const incomingTrades = TradesSchema.parse(message.data).trades
        setTrades((currentTrades) => {
          const newTrades = incomingTrades.filter(
            (incomingTrade) =>
              !currentTrades.some(
                (currentTrade) => currentTrade.id === incomingTrade.id
              )
          )
          return newTrades.concat(currentTrades)
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
            topic: { type: 'Trades' }
          })
        )
      }
    }
  }, [ws])

  return (
    <Widget
      title={'Trade History'}
      contents={
        <>
          <div className="h-96 overflow-scroll">
            <table className="relative w-full text-left text-sm">
              <thead className="sticky top-0 bg-black">
                <tr key="header">
                  <th className="min-w-32">Date</th>
                  <th className="min-w-16 pl-4">Side</th>
                  <th className="min-w-20 pl-4">Amount</th>
                  <th className="min-w-20 pl-4">Market</th>
                  <th className="min-w-20 pl-4">Price</th>
                  <th className="min-w-20 pl-4">Fee</th>
                </tr>
                <tr key="header-divider">
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                </tr>
              </thead>
              <tbody>
                {trades.map((trade) => {
                  return (
                    <tr
                      key={trade.id}
                      className="duration-200 ease-in-out hover:cursor-default hover:bg-mutedGray"
                    >
                      <td>{format(trade.timestamp, 'MM/dd HH:mm:ss')}</td>
                      <td className="pl-4">{trade.side}</td>
                      <td className="pl-4">{formatUnits(trade.amount, 18)}</td>
                      <td className="pl-4">{trade.marketId}</td>
                      <td className="pl-4">{trade.price}</td>
                      <td className="pl-4">
                        {formatUnits(trade.feeAmount, 18)}
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
