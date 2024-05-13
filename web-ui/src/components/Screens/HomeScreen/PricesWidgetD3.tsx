import { Widget } from 'components/common/Widget'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { OHLC, OHLCDuration, pricesTopic, Publishable } from 'websocketMessages'
import { mergeOHLC, ohlcDurationsMs } from 'utils/pricesUtils'
import { useWebsocketSubscription } from 'contexts/websocket'
import { produce } from 'immer'
import * as d3 from 'd3'

import { Market } from 'markets'
import SymbolIcon from 'components/common/SymbolIcon'
import { classNames } from 'utils'
import { useMeasure } from 'react-use'
import { addDuration, maxDate, subtractDuration } from 'utils/dateUtils'

enum PricesInterval {
  PT1H = '1h',
  PT6H = '6h',
  P1D = '1d',
  P7D = '7d',
  P1M = '1m',
  P6M = '6m',
  YTD = 'YTD'
}

const intervalToOHLCDuration: { [key in PricesInterval]: OHLCDuration } = {
  [PricesInterval.PT1H]: 'P1M',
  [PricesInterval.PT6H]: 'P5M',
  [PricesInterval.P1D]: 'P15M',
  [PricesInterval.P7D]: 'P1H',
  [PricesInterval.P1M]: 'P4H',
  [PricesInterval.P6M]: 'P1D',
  [PricesInterval.YTD]: 'P1D'
}

function subtractInterval(date: Date, interval: PricesInterval): Date {
  if (interval === PricesInterval.YTD) {
    return new Date(date.getFullYear(), 0, 1)
  } else {
    const number = {
      [PricesInterval.PT1H]: 60 * 60 * 1000,
      [PricesInterval.PT6H]: 6 * 60 * 60 * 1000,
      [PricesInterval.P1D]: 24 * 60 * 60 * 1000,
      [PricesInterval.P7D]: 7 * 24 * 60 * 60 * 1000,
      [PricesInterval.P1M]: 30 * 24 * 60 * 60 * 1000,
      [PricesInterval.P6M]: 182 * 24 * 60 * 60 * 1000
    }[interval]
    return new Date(date.getTime() - number)
  }
}

export function PricesWidgetD3({ market }: { market: Market }) {
  const [ref, { width }] = useMeasure()

  const [interval, setInterval] = useState<PricesInterval | null>(
    PricesInterval.PT6H
  )
  const [duration, setDuration] = useState<OHLCDuration>('P5M')
  const [ohlc, setOhlc] = useState<OHLC[]>([])
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  useWebsocketSubscription({
    topics: useMemo(
      () => [pricesTopic(market.id, duration)],
      [market, duration]
    ),
    handler: useCallback(
      (message: Publishable) => {
        if (message.type === 'Prices') {
          if (message.full) {
            setOhlc(mergeOHLC([], message.ohlc, duration))
          } else {
            setOhlc(
              produce((draft) => {
                mergeOHLC(draft, message.ohlc, duration)
              })
            )
          }
          setLastUpdated(new Date())
        }
      },
      [duration]
    )
  })

  function lastPrice(): string {
    if (ohlc.length > 0) {
      return ohlc[ohlc.length - 1].close.toFixed(
        market.tickSize.decimalPlaces() + 1
      )
    } else {
      return ''
    }
  }

  return (
    <Widget
      wrapperRef={ref}
      contents={
        <div className="min-h-[600px]">
          <div className="flex flex-row align-middle">
            <Title market={market} price={lastPrice()} />
          </div>
          <div className="flex w-full place-items-center justify-between py-4 text-sm">
            <div className="text-left">
              <IntervalsDisplay
                selectedInterval={interval}
                selectedDuration={duration}
                onChange={(int: PricesInterval) => {
                  setDuration(intervalToOHLCDuration[int])
                  setInterval(int)
                }}
              />
            </div>
            <div className="text-right text-xs text-darkBluishGray2">
              <LastUpdated lastUpdated={lastUpdated} />
            </div>
          </div>
          <div className="size-full min-h-[500px] p-4">
            <OHLCChart
              ohlc={ohlc}
              params={{
                width: Math.max(width - 60, 0), // paddings
                height: 500,
                interval: interval,
                duration: duration,
                onIntervalReset: () => setInterval(null)
              }}
            />
          </div>
        </div>
      }
    />
  )
}

type PricesParameters = {
  width: number
  height: number
  interval: PricesInterval | null
  duration: OHLCDuration
  onIntervalReset: () => void
}

function OHLCChart({
  params,
  ohlc
}: {
  params: PricesParameters
  ohlc: OHLC[]
}) {
  const ref = useRef<SVGSVGElement>(null)
  const zoomRef = useRef(d3.zoomIdentity)
  const intervalRef = useRef<PricesInterval>(
    params.interval ? params.interval : PricesInterval.PT6H
  )
  useEffect(() => {
    if (params.interval) {
      // store new interval and reset zoom
      intervalRef.current = params.interval
      zoomRef.current = d3.zoomIdentity
    }
  }, [params.interval])

  const margin = { top: 0, bottom: 15, left: 0, right: 30 }
  const innerWidth = params.width - margin.left - margin.right
  const innerHeight = params.height - margin.top - margin.bottom

  const svg = d3.select(ref.current)
  const domainStart = subtractInterval(new Date(), intervalRef.current)
  const adjustedDomainStart =
    ohlc.length > 0
      ? maxDate(
          subtractDuration(ohlc[0].start, ohlcDurationsMs[params.duration] * 2),
          domainStart
        )
      : domainStart
  // setup and position scales
  const xScale = d3
    .scaleTime()
    .domain([
      adjustedDomainStart,
      addDuration(new Date(), ohlcDurationsMs[params.duration] * 2)
    ])
    .range([0, innerWidth])
  const xAxis = d3
    .axisBottom(xScale)
    .tickSizeOuter(0)
    .tickSizeInner(-innerHeight)

  const yScale = d3.scaleLinear().range([innerHeight, 0])
  const yAxis = d3.axisRight(yScale).tickSize(0)
  const yAxisGrid = d3
    .axisRight(yScale)
    .tickSizeOuter(0)
    .tickSizeInner(-innerWidth)

  // setup zoom and panning
  const zoom = d3
    .zoom()
    // limit panning
    .translateExtent([
      [ohlc.length > 0 ? xScale(ohlc[0].start) - 1000 : -1000, innerHeight],
      [innerWidth * 1.15 + (margin.left + margin.right), innerHeight]
    ])
    // limit zoom-in/out
    .scaleExtent([0.2, 5])
    .on('zoom', (event: d3.D3ZoomEvent<SVGGElement, OHLC>) => {
      if (zoomRef.current.k != event.transform.k && event.transform.k != 1) {
        params.onIntervalReset()
      }
      zoomRef.current = event.transform
      drawChart(event.transform.rescaleX(xScale))
    })

  function drawChart(newXScale: d3.ScaleTime<number, number, never>) {
    // calculate visible range
    const visibleData: OHLC[] = ohlc.filter((d) => {
      return (
        // add some pixels for smooth bar to slide outside of viewport
        newXScale(d.start) >= -25 && newXScale(d.start) <= innerWidth + 25
      )
    })
    if (visibleData.length == 0) return

    // scale x axis
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.x-axis').call(xAxis.scale(newXScale))

    // scale y axis
    const yMin = d3.min(visibleData, (d) => d.low)
    const yMax = d3.max(visibleData, (d) => d.high)
    if (yMin && yMax) {
      yScale.domain([yMin, yMax])
    }
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.y-axis').call(yAxis.scale(yScale))
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.y-axis-grid').call(yAxisGrid.scale(yScale))

    // calculate candle width
    const candleWidth =
      newXScale.length >= 2
        ? (newXScale(ohlc[1].start) - newXScale(ohlc[0].start)) * 0.6
        : 1
    const lineWidth = candleWidth * 0.2

    // select ohlc candles
    const candles = svg
      .selectAll('.y-axis-ohlc')
      .selectAll('.ohlc')
      .data(visibleData, (d) => (d as OHLC).start.toString())

    // remove all candles that are not in visible data
    candles.exit().remove()

    // add groups for new elements
    const candlesEnter = candles.enter().append('g').attr('class', 'ohlc')

    // update positions
    candlesEnter
      .append('rect')
      .attr('class', 'range')
      .merge(candles.select('.range'))
      .attr('x', (d) => newXScale(d.start) - lineWidth / 2)
      .attr('width', lineWidth)
      .attr('y', (d) => Math.min(yScale(d.low), yScale(d.high)))
      .attr(
        'height',
        (d) =>
          Math.max(yScale(d.low), yScale(d.high)) -
          Math.min(yScale(d.low), yScale(d.high))
      )
      .attr('rx', candleWidth / 10)
      .attr('fill', (d) => (d.close > d.open ? '#39CF63' : '#FF5A50'))

    candlesEnter
      .append('rect')
      .attr('class', 'open-close')
      .merge(candles.select('.open-close'))
      .attr('x', (d) => newXScale(d.start) - candleWidth / 2)
      .attr('width', candleWidth)
      .attr('y', (d) => {
        const height =
          Math.max(yScale(d.open), yScale(d.close)) -
          Math.min(yScale(d.open), yScale(d.close))

        const y = Math.min(yScale(d.open), yScale(d.close))
        if (height < lineWidth) {
          // place exactly in the middle
          return y - (lineWidth - height) / 2
        } else {
          return y
        }
      })
      .attr('height', (d) => {
        const height =
          Math.max(yScale(d.open), yScale(d.close)) -
          Math.min(yScale(d.open), yScale(d.close))

        if (height < lineWidth) {
          return lineWidth
        } else {
          return height
        }
      })
      .attr('rx', candleWidth / 10)
      .attr('fill', (d) => (d.close >= d.open ? '#39CF63' : '#FF5A50'))
  }

  // listen to zoom events, and then call transform first time to draw the graph
  // @ts-expect-error @definitelytyped/no-unnecessary-generics
  svg.call(zoom).call(zoom.transform, zoomRef.current)

  return (
    <svg
      ref={ref}
      width={params.width}
      height={params.height}
      style={{ overflow: 'hidden' }}
    >
      <g
        className="x-axis text-xs text-darkBluishGray4"
        transform={`translate(0,${innerHeight})`}
      />
      <g className="y-axis-grid" transform={`translate(${innerWidth},0)`} />
      <g className="y-axis-ohlc" />
      <g className="y-axis-bg">
        <rect
          x={innerWidth + 10}
          y="0"
          width={margin.right}
          height={innerHeight}
        />
      </g>
      <g
        className="y-axis text-xs text-darkBluishGray4"
        transform={`translate(${innerWidth},0)`}
      />
    </svg>
  )
}

function Title({ market, price }: { market: Market; price: string }) {
  return (
    <div className="flex w-full justify-between text-xl font-semibold">
      <div className="place-items-center text-left">
        <SymbolIcon
          symbol={market.baseSymbol.name}
          className="inline-block size-7"
        />
        <SymbolIcon
          symbol={market.quoteSymbol.name}
          className="mr-4 inline-block size-7"
        />
        {market.baseSymbol.name}
        <span className="">/</span>
        {market.quoteSymbol.name}
        <span className="ml-4">Price</span>
      </div>
      <div className="flex place-items-center gap-4 text-right">{price}</div>
    </div>
  )
}

function LastUpdated({ lastUpdated }: { lastUpdated: Date | null }) {
  const formatDate = (date: Date) => {
    const year = date.getFullYear()
    const month = (date.getMonth() + 1).toString().padStart(2, '0')
    const day = date.getDate().toString().padStart(2, '0')
    const hours = date.getHours()
    const minutes = date.getMinutes().toString().padStart(2, '0')

    const isPM = hours >= 12
    const formattedHours = ((hours + 11) % 12) + 1 // Convert 24h to 12h
    const amPm = isPM ? 'PM' : 'AM'

    return `Page Last updated ${year}/${month}/${day} ${formattedHours}:${minutes} ${amPm}`
  }

  return <div>{lastUpdated ? formatDate(lastUpdated) : ''}</div>
}

function IntervalsDisplay({
  selectedInterval,
  selectedDuration,
  onChange
}: {
  selectedInterval: PricesInterval | null
  selectedDuration: OHLCDuration
  onChange: (interval: PricesInterval) => void
}) {
  // Convert the enum into an array of values for rendering
  const intervalValues = Object.values(PricesInterval)

  return (
    <div className="flex flex-row gap-2">
      {intervalValues.map((value, index) => (
        <button
          key={index}
          className={classNames(
            'w-11 bg-darkBluishGray8 rounded transition-colors duration-300 ease-in-out',
            intervalValues[index] == selectedInterval
              ? 'border text-primary4'
              : 'text-darkBluishGray3 hover:text-white'
          )}
          onClick={() => onChange(intervalValues[index])}
        >
          {value}
        </button>
      ))}
      <span className="pl-10 text-xs text-darkBluishGray3">
        ({selectedDuration})
      </span>
    </div>
  )
}
