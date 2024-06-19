import { Widget } from 'components/common/Widget'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Spinner from 'components/common/Spinner'
import {
  Direction,
  LastTrade,
  OrderBook,
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
  tickSize: Decimal
  price: Decimal
  buySize: Decimal
  sellSize: Decimal
}

export function OrderBookWidget({
  market,
  side,
  height
}: {
  market: Market
  side: OrderSide
  height: number
}) {
  const [rawOrderBook, setRawOrderBook] = useState<OrderBook>()
  const [orderBookChartEntries, setOrderBookChartEntries] = useState<
    OrderBookChartEntry[]
  >([])
  const [ref, { width }] = useMeasure()

  useWebsocketSubscription({
    topics: useMemo(() => [orderBookTopic(market.id)], [market.id]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'OrderBook') {
        setRawOrderBook(message)
      }
    }, [])
  })

  // change of market causes re-calculation of the inflated OrderBook before new OrderBook state is loaded.
  // when tick size changes from 1 to 0.001 this causes massive chart update, therefore resetting state.
  const tickSize = useMemo(() => {
    setRawOrderBook(undefined)
    return market.tickSize
  }, [market])

  useEffect(() => {
    function invertChartEntryPrices(
      entries: OrderBookChartEntry[]
    ): OrderBookChartEntry[] {
      return entries.map((entry) => {
        return {
          tickSize: invertTickSize(tickSize, entry.price),
          price: new Decimal(1).div(entry.price).toDecimalPlaces(15),
          buySize: entry.sellSize.mul(entry.price),
          sellSize: entry.buySize.mul(entry.price)
        }
      })
    }

    function invertTickSize(tickSize: Decimal, price: Decimal): Decimal {
      const currentReciprocalPrice = new Decimal(1).div(price)
      const nextReciprocalPrice = new Decimal(1).div(price.plus(tickSize))
      return new Decimal(
        currentReciprocalPrice.minus(nextReciprocalPrice)
      ).toDecimalPlaces(15)
      // Inverted tick size used to calculate order book bar height on the reciprocal scale. Therefore, high precision is required.
    }

    if (rawOrderBook) {
      // find out min and max price
      let minBuyPrice = rawOrderBook.buy.reduce(
        (min, d) => Decimal.min(min, new Decimal(d.price)),
        new Decimal(rawOrderBook.last.price)
      )
      let maxSellPrice = rawOrderBook.sell.reduce(
        (max, d) => Decimal.max(max, new Decimal(d.price)),
        new Decimal(rawOrderBook.last.price)
      )

      // add 2 ticks on both sides as buffer area
      minBuyPrice = minBuyPrice.minus(tickSize.mul(new Decimal(2)))
      maxSellPrice = maxSellPrice.plus(tickSize.mul(new Decimal(2)))

      let price = minBuyPrice
      const allPriceLevels: Decimal[] = []
      while (price.lessThanOrEqualTo(maxSellPrice)) {
        allPriceLevels.push(price)
        price = price.plus(tickSize)
      }

      // inflate order book to have a record for every level
      // this is needed for an inverted order book where the scale becomes non-linear (1/price)
      const allLevelAmounts: OrderBookChartEntry[] = allPriceLevels.map(
        (price) => {
          const buyEntry = rawOrderBook.buy.find((d) =>
            new Decimal(d.price).eq(price)
          ) || {
            price,
            size: 0
          }
          const sellEntry = rawOrderBook.sell.find((d) =>
            new Decimal(d.price).eq(price)
          ) || {
            price,
            size: 0
          }
          return {
            tickSize: tickSize,
            price: price,
            buySize: new Decimal(buyEntry.size),
            sellSize: new Decimal(sellEntry.size)
          }
        }
      )

      // and invert prices when needed
      if (side === 'Sell') {
        setOrderBookChartEntries(invertChartEntryPrices(allLevelAmounts))
      } else {
        setOrderBookChartEntries(allLevelAmounts)
      }
    } else {
      setOrderBookChartEntries([])
    }
  }, [tickSize, rawOrderBook, side])

  const orderBookLastTrade: LastTrade = useMemo(() => {
    function invertLast(last: LastTrade): LastTrade {
      const lastPrice = parseFloat(last.price)
      return {
        price: (1.0 / lastPrice).toFixed(
          (tickSize.decimalPlaces() === 0 ? 5 : tickSize.decimalPlaces()) +
            lastPrice.toFixed(0).length +
            1
        ),
        direction:
          last.direction === 'Up'
            ? 'Down'
            : last.direction === 'Down'
              ? 'Up'
              : 'Unchanged'
      }
    }

    if (rawOrderBook) {
      if (side === 'Sell') {
        return invertLast(rawOrderBook.last)
      } else {
        return rawOrderBook.last
      }
    } else {
      return {
        price: '0.0',
        direction: 'Unchanged'
      }
    }
  }, [tickSize, rawOrderBook, side])

  const tickDecimals = useMemo(() => {
    if (side === 'Sell') {
      const tickDecimals =
        tickSize.decimalPlaces() === 0 ? 5 : tickSize.decimalPlaces()
      const lastPriceDecimals = rawOrderBook
        ? rawOrderBook.last.price.split('.')[0].length
        : 0
      return tickDecimals + lastPriceDecimals
    } else {
      return tickSize.decimalPlaces()
    }
  }, [tickSize, rawOrderBook, side])

  return (
    <Widget
      id="order-book"
      wrapperRef={ref}
      contents={
        rawOrderBook && orderBookChartEntries.length != 0 ? (
          rawOrderBook.buy.length === 0 && rawOrderBook.sell.length === 0 ? (
            <div className="text-center">Empty</div>
          ) : (
            <OrderBookChart
              tickDecimals={tickDecimals}
              quantitySymbol={
                side === 'Sell' ? market.quoteSymbol : market.baseSymbol
              }
              orderBook={orderBookChartEntries}
              lastTrade={orderBookLastTrade}
              width={Math.max(width - 32, 100)}
              height={Math.max(height - 32, 400)}
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
  lastTrade,
  tickDecimals,
  quantitySymbol,
  width,
  height
}: {
  orderBook: OrderBookChartEntry[]
  lastTrade: LastTrade
  tickDecimals: number
  quantitySymbol: TradingSymbol | undefined
  width: number
  height: number
}) {
  const svgRef = useRef<SVGSVGElement>(null)

  // Rendered order book levels are sub-pixel separated. It's caused by anti-aliasing and can be avoided by specifying `shape-rendering`
  const svg = d3.select(svgRef.current) //.attr('shape-rendering', 'crispEdges')

  // Add lastTrade.price to the hidden component on y-axis to measure actual width applied styles.
  // The resulting measured width is used to calculate dynamically left margin.
  // This way in an alternative to using fixed number of pixels per symbol.
  const measuredText = svg
    .select('.y-axis')
    .append('text')
    .style('visibility', 'hidden')
    .text(lastTrade.price)
  const lastPriceLabelWidth = measuredText.node()?.getBBox().width ?? 0
  measuredText.remove()

  const margin = {
    top: 20,
    bottom: 0,
    left: lastPriceLabelWidth + 10,
    right: 0
  }
  const innerWidth = width - margin.left - margin.right
  const innerHeight = height - margin.top - margin.bottom

  const yScale = d3.scaleLinear()

  const lastMousePointerLocation = useRef({ mouseX: 0, mouseY: 0 })
  const updateMouseProjections = useCallback(
    (mouseX: number, mouseY: number) => {
      lastMousePointerLocation.current = { mouseX: mouseX, mouseY: mouseY } // save position for passive updates (y-axis domain change)

      function findNearestPriceLevel(
        entries: OrderBookChartEntry[],
        mouseY: number,
        yScale: d3.ScaleLinear<number, number>
      ): OrderBookChartEntry {
        const mousePrice = new Decimal(
          yScale.invert(mouseY - margin.top).toFixed(10)
        )

        return entries.reduce((nearest, current) => {
          const nearestDistance = mousePrice.minus(nearest.price).abs()
          const currentDistance = mousePrice.minus(current.price).abs()

          return currentDistance.lessThan(nearestDistance) ? current : nearest
        }, entries[0])
      }

      const nearestPriceLevel = findNearestPriceLevel(orderBook, mouseY, yScale)
      const levelAmount = Decimal.max(
        nearestPriceLevel.sellSize,
        nearestPriceLevel.buySize
      )

      let totalAmount = new Decimal(0)
      if (nearestPriceLevel.price.gt(new Decimal(lastTrade.price))) {
        totalAmount = orderBook
          .filter((d) => d.price.lte(nearestPriceLevel.price))
          .reduce((acc, d) => acc.plus(d.sellSize), new Decimal(0))
      }
      if (nearestPriceLevel.price.lt(new Decimal(lastTrade.price))) {
        totalAmount = orderBook
          .filter((d) => d.price.gte(nearestPriceLevel.price))
          .reduce((acc, d) => acc.plus(d.buySize), new Decimal(0))
      }

      const svgMouseProjection = svg.select('.y-axis-mouse-projection')
      const formattedLevelAmount = levelAmount.toFixed(tickDecimals + 1)

      // Add formatted level amount to the hidden component (styles are applied) to measure actual width.
      // Component is removed after measurement is done. The resulting width is used below to position labels.
      // This way is an alternative to using fixed number of pixels per symbol.
      const measuredText = svgMouseProjection
        .append('text')
        .style('visibility', 'hidden')
        .text(formattedLevelAmount)
      const labelWidth = (measuredText.node()?.getBBox().width ?? 0) * 0.95 // take 95% of calculated width
      measuredText.remove()

      const tooltip = svgMouseProjection
        .classed('hidden', mouseY < margin.top)
        .attr(
          'transform',
          `translate(0,${
            yScale(nearestPriceLevel.price.toNumber()) + margin.top
          })`
        )
      tooltip.select('rect').attr('x', `${innerWidth - labelWidth - 10}`)
      tooltip
        .select('text#line1')
        .text('Price ' + nearestPriceLevel.price.toFixed(tickDecimals))
        .attr('transform', `translate(${innerWidth - labelWidth - 4},-8)`)
      tooltip
        .select('text#line2')
        .text('Amount ' + levelAmount.toFixed(tickDecimals + 1))
        .attr('transform', `translate(${innerWidth - labelWidth - 4},4)`)
      tooltip
        .select('text#line3')
        .text('Total ' + totalAmount.toFixed(tickDecimals + 1))
        .attr('transform', `translate(${innerWidth - labelWidth - 4},16)`)
    },
    [innerWidth, lastTrade, margin.top, orderBook, svg, tickDecimals, yScale]
  )

  const drawChart = useCallback(() => {
    const minPrice = d3.min(orderBook, (d) => d.price.toNumber())!
    const maxPrice = d3.max(orderBook, (d) => d.price.toNumber())!
    const maxAmount = d3.max(orderBook, (d) =>
      Decimal.max(d.buySize, d.sellSize).toNumber()
    )!

    const xScale = d3
      .scaleLinear()
      .domain([0, maxAmount * 1.2]) // add 20% to x-scale's domain, it looks nicer
      .range([0, innerWidth])

    yScale.domain([minPrice, maxPrice]).range([innerHeight, 0])

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
      .tickFormat((d: d3.NumberValue) => d.valueOf().toFixed(tickDecimals))

    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.x-axis').call(xAxis)
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.y-axis').call(yAxis)

    // update last price tracker
    const lastPriceStyle = directionStyle(lastTrade.direction)
    const lastPriceGroup = svg
      .select('.y-axis-order-book-last-price')
      .classed('hidden', !lastTrade.price)
      // .transition()
      // .duration(50)
      .attr('transform', `translate(0,${yScale(parseFloat(lastTrade.price))})`)
    lastPriceGroup
      .select('text')
      .text(lastTrade.price)
      .attr('fill', lastPriceStyle.textColor)

    // draw levels
    function barHeight(
      from: number,
      to: number,
      scale: d3.ScaleLinear<number, number>
    ): number {
      return Math.abs(scale(from) - scale(to))
    }

    const bars = svg
      .selectAll('.order-book')
      .selectAll('.order-bar')
      .data(orderBook, (d) => (d as OrderBookChartEntry).price.toString())
    bars.exit().remove()
    bars
      .enter()
      .append('g')
      .attr('class', 'order-bar')
      .append('rect')
      .attr('class', 'bar')
      .merge(bars.select('.bar'))
      // .transition()
      // .duration(50)
      .attr('x', 0)
      .attr(
        'y',
        (d) =>
          yScale(d.price.toNumber()) -
          barHeight(d.tickSize.toNumber(), d.tickSize.toNumber() * 2, yScale) /
            2
      )
      .attr('height', (d) =>
        barHeight(d.tickSize.toNumber(), d.tickSize.toNumber() * 2, yScale)
      )
      .attr('width', (d) => {
        const result = xScale(Decimal.max(d.buySize, d.sellSize).toNumber())
        // Observed negative rect width and as a result errors in then console.
        // It looks like viewport is reduced to single pixel when browser tab is inactive for some time
        // and because of the left margin scale's pixel range is being inverted (e.g [0, -53])
        return Math.max(result, 0)
      })
      .attr('fill', (d) =>
        d.buySize.greaterThan(d.sellSize) ? '#39CF63' : '#FF5A50'
      )

    // redraw mouse projection to stick to ticks after scale has been adjusted
    updateMouseProjections(
      lastMousePointerLocation.current.mouseX,
      lastMousePointerLocation.current.mouseY
    )
  }, [
    innerHeight,
    innerWidth,
    lastTrade,
    orderBook,
    svg,
    tickDecimals,
    updateMouseProjections,
    yScale
  ])

  function hideMouseProjections() {
    svg.select('.x-axis-mouse-projection').classed('hidden', true)
    svg.select('.y-axis-mouse-projection').classed('hidden', true)
  }

  useEffect(() => {
    drawChart()
  }, [orderBook, width, height, drawChart])

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
    <div className="h-full">
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
