import { Widget } from 'components/common/Widget'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Spinner from 'components/common/Spinner'
import {
  Direction,
  LastTrade,
  OrderBook,
  OrderBookEntry,
  orderBookTopic,
  Publishable
} from 'websocketMessages'
import { useWebsocketSubscription } from 'contexts/websocket'
import { useMeasure } from 'react-use'
import { OrderSide } from 'apiClient'
import { Market } from 'markets'
import * as d3 from 'd3'
import Decimal from 'decimal.js'
import { useGesture } from '@use-gesture/react'
import TradingSymbol from 'tradingSymbol'
import SymbolIcon from 'components/common/SymbolIcon'

interface OrderBookChartEntry {
  price: Decimal
  buySize: Decimal
  sellSize: Decimal
}

export function OrderBookWidget({
  market,
  side
}: {
  market: Market
  side: OrderSide
}) {
  const [rawOrderBook, setRawOrderBook] = useState<OrderBook>()
  const [ref, { width }] = useMeasure()

  useWebsocketSubscription({
    topics: useMemo(() => [orderBookTopic(market.id)], [market.id]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'OrderBook') {
        setRawOrderBook(message)
      }
    }, [])
  })

  function invertPrices(entries: OrderBookEntry[]): OrderBookEntry[] {
    return entries.map((entry) => {
      const price = parseFloat(entry.price)
      const [integerPart] = entry.price.split('.')
      return {
        price: (1.0 / price).toFixed(4 + integerPart.length),
        size: entry.size * price
      }
    })
  }

  function invertLast(last: LastTrade): LastTrade {
    return {
      price: (1.0 / parseFloat(last.price)).toFixed(6),
      direction:
        last.direction === 'Up'
          ? 'Down'
          : last.direction === 'Down'
            ? 'Up'
            : 'Unchanged'
    }
  }

  const orderBook = useMemo(() => {
    if (side === 'Sell') {
      if (rawOrderBook) {
        return {
          type: rawOrderBook.type,
          buy: invertPrices(rawOrderBook.sell).toReversed(),
          sell: invertPrices(rawOrderBook.buy).toReversed(),
          last: invertLast(rawOrderBook.last)
        }
      } else {
        return rawOrderBook
      }
    } else {
      return rawOrderBook
    }
  }, [rawOrderBook, side])

  const tickSize: undefined | Decimal = useMemo(() => {
    if (side === 'Sell') {
      if (rawOrderBook) {
        const lastPrice = parseFloat(rawOrderBook.last.price)
        return new Decimal(
          1 / lastPrice - 1 / (lastPrice + market.tickSize.toNumber())
        )
      } else {
        return market.tickSize
      }
    } else {
      return market.tickSize
    }
  }, [market.tickSize, rawOrderBook, side])

  return (
    <Widget
      id="order-book"
      wrapperRef={ref}
      contents={
        orderBook ? (
          orderBook.buy.length === 0 && orderBook.sell.length === 0 ? (
            <div className="text-center">Empty</div>
          ) : (
            <OrderBookChart
              tickSize={tickSize}
              quantitySymbol={
                side === 'Sell' ? market.quoteSymbol : market.baseSymbol
              }
              orderBook={orderBook}
              width={Math.max(width - 40, 0)}
              height={465}
            />
          )
        ) : (
          <Spinner />
        )
      }
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

function OrderBookChart({
  orderBook,
  tickSize,
  quantitySymbol,
  width,
  height
}: {
  orderBook: OrderBook
  tickSize: Decimal
  quantitySymbol: TradingSymbol | undefined
  width: number
  height: number
}) {
  const svgRef = useRef<SVGSVGElement>(null)
  const svg = d3.select(svgRef.current).attr('shape-rendering', 'crispEdges')

  const tickDecimals = tickSize.decimalPlaces()
  console.log('tickDecimals', tickDecimals)

  // measure actual last price label width to determine left margin
  const tempText = svg
    .select('.y-axis')
    .append('text')
    .style('visibility', 'hidden')
    .text(orderBook.last.price)
  const maxLabelWidth = tempText.node()?.getBBox().width ?? 35
  tempText.remove()

  const margin = {
    top: 20,
    bottom: 0,
    left: maxLabelWidth + 10,
    right: 0
  }
  const innerWidth = width - margin.left - margin.right
  const innerHeight = height - margin.top - margin.bottom

  const yScale = d3.scaleLinear()

  const lastMousePointerLocation = useRef({ mouseX: 0, mouseY: 0 })
  const updateMouseProjections = useCallback(
    (mouseX: number, mouseY: number) => {
      lastMousePointerLocation.current = { mouseX: mouseX, mouseY: mouseY } // save position for passive update

      const tickSizeNumber = tickSize.toNumber()
      const price = yScale.invert(mouseY - margin.top)

      const levelPrice = new Decimal(Math.round(price / tickSizeNumber)).mul(
        tickSizeNumber
      )

      let levelAmount = 0
      let totalAmount = 0

      if (levelPrice >= new Decimal(orderBook.last.price)) {
        const level = orderBook.sell.find((d) =>
          new Decimal(d.price).eq(levelPrice)
        )
        levelAmount = level ? level.size : 0

        const levels = orderBook.sell.filter((d) =>
          new Decimal(d.price).lte(levelPrice)
        )
        totalAmount = levels.reduce((acc, d) => acc + d.size, 0)
      }
      if (levelPrice <= new Decimal(orderBook.last.price)) {
        const level = orderBook.buy.find((d) =>
          new Decimal(d.price).eq(levelPrice)
        )
        levelAmount = level ? level.size : 0

        const levels = orderBook.buy.filter((d) =>
          new Decimal(d.price).gte(levelPrice)
        )
        totalAmount = levels.reduce((acc, d) => acc + d.size, 0)
      }

      const tempText = svg
        .select('.y-axis-mouse-projection')
        .append('text')
        .style('visibility', 'hidden')
        .text(levelAmount.toFixed(tickDecimals + 1))
      const maxLabelWidth = (tempText.node()?.getBBox().width ?? 35) * 0.95 + 7
      tempText.remove()

      const tooltip = svg
        .select('.y-axis-mouse-projection')
        .classed('hidden', mouseY < margin.top)
        .attr(
          'transform',
          `translate(0,${yScale(levelPrice.toNumber()) + margin.top})`
        )
      tooltip.select('rect').attr('x', `${innerWidth - maxLabelWidth - 6}`)
      tooltip
        .select('text#line1')
        .text('Price ' + levelPrice.toFixed(tickDecimals))
        .attr('transform', `translate(${innerWidth - maxLabelWidth},-8)`)
      tooltip
        .select('text#line2')
        .text('Amount ' + levelAmount.toFixed(tickDecimals + 1))
        .attr('transform', `translate(${innerWidth - maxLabelWidth},4)`)
      tooltip
        .select('text#line3')
        .text('Total ' + totalAmount.toFixed(tickDecimals + 1))
        .attr('transform', `translate(${innerWidth - maxLabelWidth},16)`)
    },
    [innerWidth, margin.top, orderBook, svg, tickDecimals, tickSize, yScale]
  )

  const drawChart = useCallback(() => {
    const prices = orderBook.buy
      .map((d) => parseFloat(d.price))
      .concat(orderBook.sell.map((d) => parseFloat(d.price)))
    const maxAmount = d3.max(
      orderBook.buy.concat(orderBook.sell),
      (d) => d.size
    )!

    const xScale = d3
      .scaleLinear()
      .domain([0, maxAmount * 1.05])
      .range([0, innerWidth])

    yScale
      .domain([
        d3.min(prices)! - tickSize.toNumber() * 2,
        d3.max(prices)! + tickSize.toNumber() * 2
      ])
      .range([innerHeight, 0])

    const xAxis = d3
      .axisTop(xScale)
      .tickSizeOuter(0)
      .tickSizeInner(-innerHeight)
      .tickPadding(5)
      .tickValues(xScale.ticks(5).filter((tick) => tick !== 0)) // Filter out the '0' tick

    const yAxis = d3
      .axisLeft(yScale)
      .tickSizeOuter(0)
      .tickSizeInner(0)
      .tickPadding(10)
      .ticks(7)
      .tickFormat((d: d3.NumberValue) => {
        return d.valueOf().toFixed(tickDecimals)
      })

    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.x-axis').call(xAxis)
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.y-axis').call(yAxis)

    // update last price tracker
    const lastPriceStyle = directionStyle(orderBook.last.direction)
    const lastPriceGroup = svg
      .select('.y-axis-order-book-last-price')
      .classed('hidden', !orderBook.last.price)
      .transition()
      .duration(150)
      .attr(
        'transform',
        `translate(0,${yScale(parseFloat(orderBook.last.price))})`
      )
    lastPriceGroup
      .select('text')
      .text(orderBook.last.price)
      .attr('fill', lastPriceStyle.textColor)

    // draw levels
    const barHeight = absDistanceOnAxis(
      tickSize.toNumber(),
      tickSize.toNumber() * 2,
      yScale
    )

    const minBuyPrice = orderBook.buy.reduce(
      (min, d) => Decimal.min(min, new Decimal(d.price)),
      new Decimal(orderBook.last.price)
    )
    const maxSellPrice = orderBook.sell.reduce(
      (max, d) => Decimal.max(max, new Decimal(d.price)),
      new Decimal(orderBook.last.price)
    )
    let price = minBuyPrice
    const allPriceLevels: Decimal[] = []
    while (price.lessThanOrEqualTo(maxSellPrice)) {
      allPriceLevels.push(price)
      price = price.plus(tickSize)
    }

    // Create full data set with buy and sell amounts
    const fullData: OrderBookChartEntry[] = allPriceLevels.map((price) => {
      const buyEntry = orderBook.buy.find((d) =>
        new Decimal(d.price).eq(price)
      ) || {
        price,
        size: 0
      }
      const sellEntry = orderBook.sell.find((d) =>
        new Decimal(d.price).eq(price)
      ) || {
        price,
        size: 0
      }
      return {
        price,
        buySize: new Decimal(buyEntry.size),
        sellSize: new Decimal(sellEntry.size)
      }
    })

    const bars = svg
      .selectAll('.order-book')
      .selectAll('.order-bar')
      .data(fullData, (d) =>
        (d as OrderBookChartEntry).price.toFixed(tickDecimals + 1)
      )

    bars.exit().remove()

    const barsEnter = bars.enter().append('g').attr('class', 'order-bar')
    barsEnter
      .append('rect')
      .attr('class', 'buy')
      .merge(bars.select('.buy'))
      .transition()
      .duration(50)
      .attr('x', 0)
      .attr('y', (d) => yScale(d.price.toNumber()) - barHeight / 2)
      .attr('height', barHeight)
      .attr('width', (d) => xScale(d.buySize.toNumber()))
      .attr('fill', '#39CF63')

    barsEnter
      .append('rect')
      .attr('class', 'sell')
      .merge(bars.select('.sell'))
      .transition()
      .duration(50)
      .attr('x', (d) => xScale(d.buySize.toNumber())) // Position sell bars after buy bars
      .attr('y', (d) => yScale(d.price.toNumber()) - barHeight / 2)
      .attr('height', barHeight)
      .attr('width', (d) => xScale(d.sellSize.toNumber()))
      .attr('fill', '#FF5A50')

    // redraw mouse projection to stick to ticks after scale has been adjusted
    updateMouseProjections(
      lastMousePointerLocation.current.mouseX,
      lastMousePointerLocation.current.mouseY
    )
  }, [
    innerHeight,
    innerWidth,
    orderBook,
    svg,
    tickDecimals,
    tickSize,
    updateMouseProjections,
    yScale
  ])

  useEffect(() => {
    drawChart()
  }, [orderBook, width, height, drawChart])

  function hideMouseProjections() {
    svg.select('.x-axis-mouse-projection').classed('hidden', true)
    svg.select('.y-axis-mouse-projection').classed('hidden', true)
  }

  // Add gesture handling
  const gestureBindings = useGesture(
    {
      onMove: ({ xy: [clientX, clientY] }) => {
        if (svgRef.current) {
          const svgRect = svgRef.current.getBoundingClientRect()
          const mouseX = clientX - svgRect.left
          const mouseY = clientY - svgRect.top // - 1.5
          updateMouseProjections(mouseX, mouseY)
        }
      },
      onHover: ({ hovering }) => {
        if (!hovering) hideMouseProjections()
      }
    },
    {}
  )

  drawChart()

  return (
    <div className="relative">
      <svg
        ref={svgRef}
        width={width}
        height={height}
        style={{
          overflow: 'hidden'
        }}
        className="touch-none"
        {...gestureBindings()}
      >
        <g className="svg-main cursor-crosshair">
          <rect
            className="opacity-0"
            x="0"
            y="0"
            width={width}
            height={height}
          />
          <g
            className="order-book"
            transform={`translate(${margin.left},${margin.top})`}
          />
          <g
            className="x-axis text-xs text-darkBluishGray4"
            transform={`translate(${margin.left},${margin.top})`}
          />
          <g
            className="y-axis text-xs text-darkBluishGray4"
            transform={`translate(${margin.left},${margin.top})`}
          />
          <g className="y-axis-order-book-last-price hidden text-xs">
            <rect
              x={2}
              y={margin.top - 7}
              width={margin.left - 3}
              height="14"
              rx="3"
            />
            <text transform={`translate(3,${margin.top + 4})`} />
          </g>
          <g className="y-axis-mouse-projection hidden text-xs">
            <line x1={margin.left} x2={innerWidth} y1="0" y2="0" />
            <rect x={innerWidth - 44} y="-22" width="300" height="44" rx="3" />
            <text id="line1" />
            <text id="line2" />
            <text id="line3" />
          </g>
        </g>
      </svg>
      {quantitySymbol && (
        <div className="absolute right-0 top-0 flex">
          <SymbolIcon symbol={quantitySymbol} />
        </div>
      )}
    </div>
  )
}

function absDistanceOnAxis(
  from: number,
  to: number,
  scale: d3.ScaleLinear<number, number>
): number {
  return Math.abs(scale(from) - scale(to))
}
