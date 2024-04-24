import { Widget } from 'components/common/Widget'
import { calculateTickSpacing } from 'utils/orderBookUtils'
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import Spinner from 'components/common/Spinner'
import { OrderBook, Publishable, orderBookTopic } from 'websocketMessages'
import { useWebsocketSubscription } from 'contexts/websocket'
import { useWindowDimensions, widgetSize, WindowDimensions } from 'utils/layout'

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

function calculateParameters(
  orderBook: OrderBook,
  windowDimensions: WindowDimensions
): OrderBookParameters {
  const bookWidth = widgetSize(windowDimensions.width)
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

export function OrderBookWidget({ marketId }: { marketId: string }) {
  const [orderBook, setOrderBook] = useState<OrderBook>()
  const [params, setParams] = useState<OrderBookParameters>()
  const windowDimensions = useWindowDimensions()

  useWebsocketSubscription({
    topics: useMemo(() => [orderBookTopic(marketId)], [marketId]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'OrderBook') {
        setOrderBook(message)
      }
    }, [])
  })

  useEffect(() => {
    if (orderBook) {
      setParams(calculateParameters(orderBook, windowDimensions))
    }
  }, [orderBook, windowDimensions])

  return (
    <Widget
      title={'Order Book'}
      contents={<OrderBook params={params} orderBook={orderBook} />}
    />
  )
}

export function OrderBook({
  params,
  orderBook
}: {
  params: OrderBookParameters | undefined
  orderBook: OrderBook | undefined
}) {
  if (!params || !orderBook) {
    return <Spinner />
  }

  if (orderBook.buy.length === 0 && orderBook.sell.length === 0) {
    return <div className={'text-center'}>Empty</div>
  }

  return (
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
            width={Math.min(
              params.graphWidth,
              params.graphWidth * (l.size / params.maxSize)
            )}
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
        <tspan fill={orderBook.last.direction == 'Up' ? '#10A327' : '#7F1D1D'}>
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
            width={Math.min(
              params.graphWidth,
              params.graphWidth * (l.size / params.maxSize)
            )}
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
  )
}
