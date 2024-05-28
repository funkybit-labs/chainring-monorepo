import { Widget } from 'components/common/Widget'
import { calculateTickSpacing } from 'utils/orderBookUtils'
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import Spinner from 'components/common/Spinner'
import {
  OrderBook,
  Publishable,
  orderBookTopic,
  Direction,
  OrderBookEntry
} from 'websocketMessages'
import { useWebsocketSubscription } from 'contexts/websocket'
import { useMeasure } from 'react-use'

type OrderBookParameters = {
  gridLines: number
  graphWidth: number
  graphStartY: number
  barHeight: number
  barGap: number
  lastPriceHeight: number
  maxSize: number
  graphHeight: number
  buyStartY: number
  gridSpacing: number
  ticks: number[]
  buyLevels: OrderBookEntry[]
  sellLevels: OrderBookEntry[]
}

function calculateParameters(
  orderBook: OrderBook,
  width: number
): OrderBookParameters {
  const graphWidth = width - 32
  const gridLines = 6
  const graphStartY = 20
  const barHeight = 20
  const barGap = 8
  const lastPriceHeight = 50
  const maxLevels = 10
  const buyLevels = orderBook.buy.slice(0, maxLevels)
  const sellLevels = orderBook.sell.slice(-maxLevels)
  const maxSize = Math.max(
    0.00000001,
    ...sellLevels.map((l) => l.size),
    ...buyLevels.map((l) => l.size)
  )
  const graphHeight =
    graphStartY +
    lastPriceHeight +
    barHeight * (buyLevels.length + sellLevels.length) +
    barGap * (buyLevels.length + sellLevels.length - 2)
  const buyStartY =
    graphStartY +
    lastPriceHeight +
    barHeight * sellLevels.length +
    barGap * (sellLevels.length - 1)
  const gridSpacing = calculateTickSpacing(0, maxSize, gridLines)

  const ticks: number[] = []
  for (let i = 0; i <= gridLines; i++) {
    ticks.push(i * gridSpacing)
  }

  return {
    gridLines,
    graphWidth,
    graphStartY,
    barHeight,
    barGap,
    lastPriceHeight,
    // we adjust the max size to be the grid line past the last one, which keeps the grid lines
    // more stable as the values move around
    maxSize: gridSpacing * (gridLines + 1),
    graphHeight,
    buyStartY,
    gridSpacing,
    ticks,
    buyLevels,
    sellLevels
  }
}

export function OrderBookWidget({ marketId }: { marketId: string }) {
  const [orderBook, setOrderBook] = useState<OrderBook>()
  const [params, setParams] = useState<OrderBookParameters>()

  const [ref, { width }] = useMeasure()

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
      setParams(calculateParameters(orderBook, width))
    }
  }, [orderBook, width])

  return (
    <Widget
      id="order-book"
      wrapperRef={ref}
      contents={<OrderBook params={params} orderBook={orderBook} />}
    />
  )
}

function directionStyle(direction: Direction) {
  switch (direction) {
    case 'Up':
      return { textColor: '#42C66B', arrowColor: '#42C66B', symbol: ' ↗︎' }
    case 'Down':
      return { textColor: '#FF7169', arrowColor: '#FF7169', symbol: ' ↘︎' }
    case 'Unchanged':
      return { textColor: '#BABEC5', arrowColor: 'transparent', symbol: '' }
  }
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

  const direction = directionStyle(orderBook.last.direction)

  // so that we don't draw ticks through the price labels, find out how long the first label is (they should all be
  // the same size) and assume 12px / character -- dynamically measuring svg text would be more accurate but
  // complicated
  const labelWidth = (params.sellLevels[0] ?? params.buyLevels[0])?.price.length
  const minimumTickX = labelWidth * 12

  function formatTick(tick: number, gridSpacing: number): string {
    const spacingAsString = gridSpacing.toString()
    if (spacingAsString.includes('.')) {
      return tick.toFixed(spacingAsString.split('.')[1].length)
    } else {
      return tick.toFixed(0)
    }
  }

  return (
    <svg width={params.graphWidth} height={params.graphHeight}>
      {params.sellLevels.map((l, i) => (
        <Fragment key={`${l.price}`}>
          <rect
            x={0}
            width={Math.min(
              params.graphWidth,
              params.graphWidth * (l.size / params.maxSize)
            )}
            y={
              params.graphStartY + 8 + i * params.barHeight + i * params.barGap
            }
            height={params.barHeight}
            fill="#FF716933"
          />
          <text
            x={0}
            y={
              params.graphStartY +
              4 +
              (i + 1) * params.barHeight +
              i * params.barGap
            }
            fill="#FF7169"
            textAnchor="left"
            fontWeight={600}
          >
            {l.price}
          </text>
        </Fragment>
      ))}

      {params.buyLevels.map((l, i) => (
        <Fragment key={`${l.price}`}>
          <rect
            x={0}
            width={Math.min(
              params.graphWidth,
              params.graphWidth * (l.size / params.maxSize)
            )}
            y={params.buyStartY + i * params.barHeight + i * params.barGap}
            height={params.barHeight}
            fill="#42C66B33"
          />
          <text
            x={0}
            y={
              params.buyStartY +
              (i + 1) * params.barHeight +
              i * params.barGap -
              4
            }
            fill="#42C66B"
            textAnchor="left"
            fontWeight={600}
          >
            {l.price}
          </text>
        </Fragment>
      ))}
      {params.ticks.slice(1).map((tick, i) => {
        const xPos =
          (i + 1) * params.gridSpacing * (params.graphWidth / params.maxSize)
        return (
          xPos > minimumTickX && (
            <Fragment key={`tick-${i}`}>
              <text
                x={xPos}
                y={params.graphStartY}
                fill="white"
                textAnchor="middle"
              >
                {formatTick(tick, params.gridSpacing)}
              </text>
              <line
                x1={xPos}
                y1={params.graphStartY + 8}
                x2={xPos}
                y2={params.buyStartY - params.lastPriceHeight}
                stroke="white"
                strokeDasharray={8}
              />
              <line
                x1={xPos}
                y1={params.buyStartY}
                x2={xPos}
                y2={params.graphHeight}
                stroke="white"
                strokeDasharray={8}
              />
            </Fragment>
          )
        )
      })}
      <text
        x={0}
        y={params.buyStartY - 12}
        fill={direction.textColor}
        textAnchor="left"
        fontSize="24px"
      >
        {orderBook.last.price}
        <tspan fill={direction.arrowColor}>{direction.symbol}</tspan>
      </text>
    </svg>
  )
}
