import { Widget } from 'components/common/Widget'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { OHLC, OHLCDuration, pricesTopic, Publishable } from 'websocketMessages'
import { mergeOHLC, ohlcDurationsMs } from 'utils/pricesUtils'
import { useWebsocketSubscription } from 'contexts/websocket'
import { produce } from 'immer'
import * as d3 from 'd3'
import { D3ZoomEvent, scaleTime } from 'd3'

import { Market } from 'markets'
import SymbolIcon from 'components/common/SymbolIcon'
import { classNames } from 'utils'
import { useMeasure } from 'react-use'

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

const intervalToMs: { [key in PricesInterval]: number } = {
  [PricesInterval.PT1H]: 60 * 60 * 1000,
  [PricesInterval.PT6H]: 6 * 60 * 60 * 1000,
  [PricesInterval.P1D]: 24 * 60 * 60 * 1000,
  [PricesInterval.P7D]: 7 * 60 * 60 * 1000,
  [PricesInterval.P1M]: 30 * 60 * 60 * 1000,
  [PricesInterval.P6M]: 182 * 60 * 60 * 1000,
  [PricesInterval.YTD]: 364 * 60 * 60 * 1000
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
      intervalRef.current = params.interval
    }
  }, [params.interval])

  const margin = { top: 0, bottom: 15, left: 0, right: 30 }
  const innerWidth = params.width - margin.left - margin.right
  const innerHeight = params.height - margin.top - margin.bottom

  const svg = d3
    .select(ref.current)
    .attr('width', params.width)
    .attr('height', params.height)
    .style('overflow', 'hidden')

  if (svg.select('.x-axis').size() == 0 && params.width > 0) {
    // add placeholders, axes will be rendered later in drawChart
    svg.append('g').attr('class', 'x-axis text-darkBluishGray4 text-xs') // x-axis

    svg.append('g').attr('class', 'y-axis-grid') // grid
    svg.append('g').attr('class', 'y-axis-ohlc') // ohlc
    // y-axis background, relevant for ohlc to slide under
    svg
      .append('g')
      .attr('class', 'y-axis-bg')
      .append('rect')
      .attr('x', innerWidth + 10)
      .attr('y', 0)
      .attr('width', margin.right)
      .attr('height', innerHeight)
      .attr('stroke', '#1B222B')
      .attr('stroke-width', 30)

    svg.append('g').attr('class', 'y-axis text-darkBluishGray4 text-xs') // y-axis
  }

  // setup and position scales
  const xScale = scaleTime()
    .domain([
      new Date(new Date().getTime() - intervalToMs[intervalRef.current]),
      new Date(new Date().getTime() + ohlcDurationsMs[params.duration] * 2)
    ])
    .range([0, innerWidth])
  const xAxis = d3
    .axisBottom(xScale)
    .tickSizeOuter(0)
    .tickSizeInner(-innerHeight)
  svg.select('.x-axis').attr('transform', `translate(0,${innerHeight})`)

  const yScale = d3.scaleLinear().range([innerHeight, 0])
  const yAxis = d3.axisRight(yScale).tickSize(0)
  const yAxisGrid = d3
    .axisRight(yScale)
    .tickSizeOuter(0)
    .tickSizeInner(-innerWidth)
  svg.select('.y-axis').attr('transform', `translate(${innerWidth},0)`)
  svg.select('.y-axis-grid').attr('transform', `translate(${innerWidth},0)`)

  // setup zoom and panning
  const zoom = d3
    .zoom()
    // limit panning
    .translateExtent([
      [ohlc.length > 0 ? xScale(ohlc[0].start) * 1.15 : -100, innerHeight],
      [innerWidth * 1.15 + (margin.left + margin.right), innerHeight]
    ])
    // limit zoom-in/out
    .scaleExtent([0.2, 5])
    .on('zoom', (event: D3ZoomEvent<SVGGElement, OHLC>) => {
      if (zoomRef.current.k != event.transform.k && event.transform.k != 1) {
        params.onIntervalReset()
      }
      zoomRef.current = event.transform
      drawChart(event.transform.rescaleX(xScale))
    })

  // reset zoom on when updating interval
  useEffect(() => {
    if (params.interval) {
      // @ts-expect-error @definitelytyped/no-unnecessary-generics
      svg.call(zoom.transform, d3.zoomIdentity)
    }
  }, [params.interval])

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
      (newXScale(ohlc[1].start) - newXScale(ohlc[0].start)) * 0.9

    // select ohlc candles
    const candles = svg
      .selectAll('.y-axis-ohlc')
      .selectAll('.ohlc')
      .data(visibleData, (d) => (d as OHLC).start.toString())

    // remove all candles that are not in visible data
    candles.exit().remove()

    // add groups for missing elements
    const candlesEnter = candles.enter().append('g').attr('class', 'ohlc')

    // update positions
    candlesEnter
      .append('line')
      .attr('class', 'range')
      .merge(candles.select('.range'))
      .attr('x1', (d) => newXScale(d.start))
      .attr('x2', (d) => newXScale(d.start))
      .attr('y1', (d) => yScale(d.high))
      .attr('y2', (d) => yScale(d.low))
      .attr('stroke', (d) => (d.close > d.open ? '#39CF63' : '#FF5A50'))
      .attr('stroke-width', 1)

    candlesEnter
      .append('line')
      .attr('class', 'open-close')
      .merge(candles.select('.open-close'))
      .attr('x1', (d) => newXScale(d.start)) // Offset by 2 pixels to the left for open
      .attr('x2', (d) => newXScale(d.start)) // Offset by 2 pixels to the right for close
      .attr('y1', (d) =>
        Math.abs(yScale(d.open) - yScale(d.close)) < 1
          ? yScale(d.open) - 0.5
          : yScale(d.open)
      )
      .attr('y2', (d) =>
        Math.abs(yScale(d.open) - yScale(d.close)) < 1
          ? yScale(d.close) + 0.5
          : yScale(d.close)
      )
      .attr('stroke', (d) => (d.close >= d.open ? '#39CF63' : '#FF5A50'))
      .attr('stroke-width', candleWidth)
  }

  // listen to zoom events, and then call transform first time to draw the graph
  // @ts-expect-error @definitelytyped/no-unnecessary-generics
  svg.call(zoom).call(zoom.transform, zoomRef.current)

  return <svg ref={ref} />
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
