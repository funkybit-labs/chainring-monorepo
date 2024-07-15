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
import { SymbolIconSVG } from 'components/common/SymbolIcon'
import usePageVisibility from 'hooks/usePageVisibility'

interface OrderBookChartEntry {
  tickSize: Decimal
  price: Decimal
  buySize: Decimal
  sellSize: Decimal
}

const CLAMP_MAX_TICKS = 250

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
  const { isPageVisible } = usePageVisibility()

  useWebsocketSubscription({
    topics: useMemo(() => [orderBookTopic(market.id)], [market.id]),
    handler: useCallback(
      (message: Publishable) => {
        if (message.type === 'OrderBook') {
          const maxBuy =
            message.buy.length > 0
              ? message.buy
                  .map((d) => parseFloat(d.price))
                  .reduce((max, price) => (max > price ? max : price))
              : 0
          const minBuy = maxBuy - market.tickSize.toNumber() * CLAMP_MAX_TICKS

          const minSell =
            message.sell.length > 0
              ? message.sell
                  .map((d) => parseFloat(d.price))
                  .reduce((min, price) => (min < price ? min : price))
              : 0
          const maxSell = minSell + market.tickSize.toNumber() * CLAMP_MAX_TICKS

          // clamp to CLAMP_TICKS on each side
          const clamped = {
            type: message.type,
            buy: message.buy.filter((d) => parseFloat(d.price) >= minBuy),
            sell: message.sell.filter((d) => parseFloat(d.price) <= maxSell),
            last: message.last
          }

          setRawOrderBook(clamped)
        }
      },
      [market.tickSize]
    )
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
      const allPrices = rawOrderBook.buy
        .map((d) => new Decimal(d.price))
        .concat(rawOrderBook.sell.map((d) => new Decimal(d.price)))
      let minPrice, maxPrice
      if (allPrices.length > 0) {
        minPrice = allPrices.reduce((min, price) =>
          min.lessThan(price) ? min : price
        )
        maxPrice = allPrices.reduce((max, price) =>
          max.greaterThan(price) ? max : price
        )
      } else {
        // default values when there are no prices
        minPrice = new Decimal(0)
        maxPrice = new Decimal(0)
      }

      // add 2 ticks on both sides as buffer area
      minPrice = minPrice.greaterThan(new Decimal(0))
        ? minPrice.minus(tickSize.mul(new Decimal(2)))
        : minPrice
      maxPrice = maxPrice.plus(tickSize.mul(new Decimal(2)))

      let price = minPrice
      const allPriceLevels: Decimal[] = []
      while (price.lessThanOrEqualTo(maxPrice)) {
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
        price: (lastPrice === 0 ? 0 : 1.0 / lastPrice).toFixed(
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
              inverted={side === 'Sell'}
              lastTrade={orderBookLastTrade}
              width={Math.max(width - 32, 100)}
              height={Math.max(height - 32, 400)}
              renderAnimation={isPageVisible}
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
  inverted,
  lastTrade,
  tickDecimals,
  quantitySymbol,
  width,
  height,
  renderAnimation
}: {
  orderBook: OrderBookChartEntry[]
  inverted: boolean
  lastTrade: LastTrade
  tickDecimals: number
  quantitySymbol: TradingSymbol | undefined
  width: number
  height: number
  renderAnimation: boolean
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
    right: 40 // free space for the symbol icon
  }
  const innerWidth = width - margin.left - margin.right
  const innerWidthWithMarginRight = width - margin.left
  const innerHeight = height - margin.top - margin.bottom

  const yScale = inverted ? d3.scalePow().exponent(-1) : d3.scaleLinear()

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

      const totalSell = orderBook
        .filter((d) => d.price.lte(nearestPriceLevel.price))
        .reduce((acc, d) => acc.plus(d.sellSize), new Decimal(0))
      const totalBuy = orderBook
        .filter((d) => d.price.gte(nearestPriceLevel.price))
        .reduce((acc, d) => acc.plus(d.buySize), new Decimal(0))
      const totalAmount = totalSell.gt(new Decimal(0)) ? totalSell : totalBuy

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

      // make tooltip are always be visible on the screen
      const yAdjustToScreen =
        mouseY > innerHeight + margin.top - 22 // lower boundary
          ? innerHeight + margin.top - mouseY - 22
          : mouseY < margin.top + 10 // upped boundary
            ? margin.top + 10 - mouseY
            : 0
      tooltip
        .select('rect')
        .attr('x', `${innerWidthWithMarginRight - labelWidth - 10}`)
        .attr('y', yAdjustToScreen - 22)
      tooltip
        .select('text#line1')
        .text('Price ' + nearestPriceLevel.price.toFixed(tickDecimals))
        .attr(
          'transform',
          `translate(
          ${innerWidthWithMarginRight - labelWidth - 4},
          ${-8 + yAdjustToScreen}
          )`
        )
      tooltip
        .select('text#line2')
        .text('Amount ' + levelAmount.toFixed(tickDecimals + 1))
        .attr(
          'transform',
          `translate(
          ${innerWidthWithMarginRight - labelWidth - 4},
          ${4 + yAdjustToScreen}
          )`
        )
      tooltip
        .select('text#line3')
        .text('Total ' + totalAmount.toFixed(tickDecimals + 1))
        .attr(
          'transform',
          `translate(
          ${innerWidthWithMarginRight - labelWidth - 4},
          ${16 + yAdjustToScreen}
          )`
        )
    },
    [
      innerWidthWithMarginRight,
      innerHeight,
      margin.top,
      orderBook,
      svg,
      tickDecimals,
      yScale
    ]
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
    if (inverted) {
      yAxis.tickValues(evenlyPlacedReciprocalTicks(yScale, 6))
    } else {
      yAxis.ticks(7)
    }
    yAxis.tickFormat((d: d3.NumberValue) => d.valueOf().toFixed(tickDecimals))

    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.x-axis').call(xAxis)
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.y-axis').call(yAxis)

    // update last price tracker
    const parsedLastPrice = parseFloat(lastTrade.price)
    let lastPriceGroup = svg
      .select('.y-axis-order-book-last-price')
      .classed('hidden', parsedLastPrice === 0)
    if (parsedLastPrice !== 0) {
      if (renderAnimation) {
        // @ts-expect-error Selection and Transition interfaces are compatible, casting only because of generic BaseType
        lastPriceGroup = lastPriceGroup
          .transition()
          .duration(50) as typeof lastPriceGroup
      }
      lastPriceGroup.attr(
        'transform',
        `translate(0,${yScale(parsedLastPrice)})`
      )
      const lastPriceStyle = directionStyle(lastTrade.direction)
      lastPriceGroup
        .select('text')
        .text(lastTrade.price)
        .attr('fill', lastPriceStyle.textColor)
    }

    function evenlyPlacedReciprocalTicks(
      yScale: d3.ScaleLinear<number, number> | d3.ScalePower<number, number>,
      numTicks: number
    ): number[] {
      const [rangeStart, rangeEnd] = yScale.range().sort((a, b) => a - b)
      const tickRange = rangeEnd - rangeStart
      const tickInterval = (tickRange * 0.8) / (numTicks - 1)
      const startPoint = rangeStart + tickRange * 0.1

      return Array.from({ length: numTicks }, (_, i) =>
        yScale.invert(startPoint + i * tickInterval)
      )
    }

    // draw levels
    function barHeight(
      price: number,
      tickSize: number,
      scale: d3.ScaleLinear<number, number> | d3.ScalePower<number, number>
    ): number {
      return Math.abs(scale(price) - scale(price + tickSize))
    }

    const bars = svg
      .selectAll('.order-book')
      .selectAll('.order-bar')
      .data(orderBook, (d) => (d as OrderBookChartEntry).price.toString())
    bars.exit().remove()
    let barsUpdate = bars
      .enter()
      .append('g')
      .attr('class', 'order-bar')
      .append('rect')
      .attr('class', 'bar')
      .merge(bars.select('.bar'))
    if (renderAnimation) {
      // @ts-expect-error Selection and Transition interfaces are compatible, casting only because of generic BaseType
      barsUpdate = barsUpdate.transition().duration(50) as typeof barsUpdate
    }
    barsUpdate
      .attr('x', 0)
      .attr(
        'y',
        (d) =>
          yScale(d.price.toNumber()) -
          barHeight(d.price.toNumber(), d.tickSize.toNumber(), yScale) / 2
      )
      .attr('height', (d) =>
        barHeight(d.price.toNumber(), d.tickSize.toNumber(), yScale)
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
    inverted,
    lastTrade,
    orderBook,
    svg,
    tickDecimals,
    updateMouseProjections,
    yScale,
    renderAnimation
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
          {quantitySymbol && (
            <g transform={`translate(${width - 30},${5})`}>
              <SymbolIconSVG symbol={quantitySymbol} />
            </g>
          )}
          <g className="y-axis-mouse-projection hidden text-xs">
            <line
              x1={margin.left}
              x2={innerWidthWithMarginRight}
              y1="0"
              y2="0"
            />
            <rect
              x={innerWidthWithMarginRight - 44}
              y="-22"
              width="300"
              height="44"
              rx="3"
            />
            <text id="line1" />
            <text id="line2" />
            <text id="line3" />
          </g>
        </g>
      </svg>
    </div>
  )
}
