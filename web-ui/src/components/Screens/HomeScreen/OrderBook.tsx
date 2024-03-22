import { Widget } from 'components/common/Widget'
import { calculateTickSpacing } from 'utils/orderBookUtils'
import { Fragment, useEffect, useState } from 'react'
import Spinner from 'components/common/Spinner'
import { Websocket, WebsocketEvent } from 'websocket-ts'
import { OrderBook, OutgoingWSMessage } from 'ApiClient'

type OrderBookParameters = {
  bookWidth: number
  gridLines: number
  graphStartX: number
  graphEndX: number
  graphWidth: number
  graphStartY: number
  barHeight: number
  lastPriceHeight: number
  maxSize: number
  bookHeight: number
  sellStartY: number
  gridSpacing: number
  ticks: number[]
}

function calculateParameters(orderBook: OrderBook): OrderBookParameters {
  const bookWidth = 400
  const gridLines = 6
  const graphStartX = 60
  const graphEndX = bookWidth - 20
  const graphWidth = graphEndX - graphStartX
  const graphStartY = 20
  const barHeight = 18
  const lastPriceHeight = 50
  const maxSize = Math.max(
    ...orderBook.sell.map((l) => l.size),
    ...orderBook.buy.map((l) => l.size)
  )
  const bookHeight =
    graphStartY +
    lastPriceHeight +
    barHeight * (orderBook.buy.length + orderBook.sell.length)
  const sellStartY =
    graphStartY + lastPriceHeight + barHeight * orderBook.buy.length
  const gridSpacing = calculateTickSpacing(0, maxSize, gridLines)

  const ticks: number[] = []
  for (let i = 0; i <= gridLines; i++) {
    ticks.push(i * gridSpacing)
  }

  return {
    bookWidth,
    gridLines,
    graphStartX,
    graphEndX,
    graphWidth,
    graphStartY,
    barHeight,
    lastPriceHeight,
    // we adjust the max size to be the grid line past the last one, which keeps the grid lines
    // more stable as the values move around
    maxSize: gridSpacing * (gridLines + 1),
    bookHeight,
    sellStartY,
    gridSpacing,
    ticks
  }
}

export function OrderBook({ ws }: { ws: Websocket }) {
  const [orderBook, setOrderBook] = useState<OrderBook>()
  const [params, setParams] = useState<OrderBookParameters>()

  useEffect(() => {
    const subscribe = () => {
      ws.send(JSON.stringify({ type: 'Subscribe', instrument: 'BTC/ETH' }))
    }
    ws.addEventListener(WebsocketEvent.reconnect, subscribe)
    if (ws.readyState == WebSocket.OPEN) {
      subscribe()
    } else {
      ws.addEventListener(WebsocketEvent.open, subscribe)
    }
    const handleMessage = (ws: Websocket, event: MessageEvent) => {
      const message = JSON.parse(event.data) as OutgoingWSMessage
      if (message.type == 'Publish' && message.data.type == 'OrderBook') {
        setParams(calculateParameters(message.data))
        setOrderBook(message.data)
      }
    }
    ws.addEventListener(WebsocketEvent.message, handleMessage)
    return () => {
      ws.removeEventListener(WebsocketEvent.message, handleMessage)
      ws.removeEventListener(WebsocketEvent.reconnect, subscribe)
      ws.removeEventListener(WebsocketEvent.open, subscribe)
      if (ws.readyState == WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'Unsubscribe', instrument: 'BTC/ETH' }))
      }
    }
  }, [ws])

  return (
    <Widget
      title={'Order Book'}
      contents={
        orderBook && params ? (
          <svg width={params.bookWidth} height={params.bookHeight}>
            {orderBook.buy.toReversed().map((l, i) => (
              <Fragment key={`${l.price}`}>
                <text
                  x={0}
                  y={params.graphStartY + 4 + (i + 1) * params.barHeight}
                  fill="white"
                  textAnchor="left"
                >
                  {l.price}
                </text>
                <rect
                  x={params.graphStartX}
                  width={params.graphWidth * (l.size / params.maxSize)}
                  y={params.graphStartY + 8 + i * params.barHeight}
                  height={params.barHeight}
                  fill="#10A327"
                />
              </Fragment>
            ))}
            <text
              x={0}
              y={params.sellStartY - 12}
              fill="white"
              textAnchor="left"
              fontSize="24px"
            >
              {orderBook.last.price}
              <tspan
                fill={orderBook.last.direction == 'Up' ? '#10A327' : '#7F1D1D'}
              >
                {orderBook.last.direction == 'Up' ? '↑' : '↓'}
              </tspan>
            </text>
            {orderBook.sell.map((l, i) => (
              <Fragment key={`${l.price}`}>
                <text
                  x={0}
                  y={params.sellStartY + (i + 1) * params.barHeight - 4}
                  fill="white"
                  textAnchor="left"
                >
                  {l.price}
                </text>
                <rect
                  x={params.graphStartX}
                  width={params.graphWidth * (l.size / params.maxSize)}
                  y={params.sellStartY + i * params.barHeight}
                  height={params.barHeight}
                  fill="#7F1D1D"
                />
              </Fragment>
            ))}
            {params.ticks.slice(1).map((tick, i) => (
              <Fragment key={`tick-${i}`}>
                <text
                  x={
                    params.graphStartX +
                    (i + 1) *
                      params.gridSpacing *
                      (params.graphWidth / params.maxSize)
                  }
                  y={params.graphStartY}
                  fill="white"
                  textAnchor="middle"
                >
                  {tick}
                </text>
                <line
                  x1={
                    params.graphStartX +
                    (i + 1) *
                      params.gridSpacing *
                      (params.graphWidth / params.maxSize)
                  }
                  y1={params.graphStartY + 8}
                  x2={
                    params.graphStartX +
                    (i + 1) *
                      params.gridSpacing *
                      (params.graphWidth / params.maxSize)
                  }
                  y2={params.bookHeight}
                  stroke="white"
                  strokeDasharray={4}
                  strokeOpacity={0.7}
                />
              </Fragment>
            ))}
          </svg>
        ) : (
          <Spinner />
        )
      }
    />
  )
}
