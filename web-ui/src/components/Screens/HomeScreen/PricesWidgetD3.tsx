import { Widget } from 'components/common/Widget'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { OHLC, OHLCDuration, pricesTopic, Publishable } from 'websocketMessages'
import { mergeOHLC, ohlcDurationsMs } from 'utils/pricesUtils'
import { useWebsocketSubscription } from 'contexts/websocket'
import { useWindowDimensions, widgetSize, WindowDimensions } from 'utils/layout'
import { produce } from 'immer'
import * as d3 from 'd3'
import { Market } from 'markets'
import SymbolIcon from 'components/common/SymbolIcon'
import { classNames } from 'utils'

enum Interval {
  PT1H = '1h',
  PT6H = '6h',
  P1D = '1d',
  P7D = '7d',
  P1M = '1m',
  P6M = '6m',
  YTD = 'YTD'
}

const intervalToOHLCDuration: { [key in Interval]: OHLCDuration } = {
  [Interval.PT1H]: 'P1M',
  [Interval.PT6H]: 'P5M',
  [Interval.P1D]: 'P15M',
  [Interval.P7D]: 'P1H',
  [Interval.P1M]: 'P4H',
  [Interval.P6M]: 'P1D',
  [Interval.YTD]: 'P1D'
}

const intervalToMs: { [key in Interval]: number } = {
  [Interval.PT1H]: 60 * 60 * 1000,
  [Interval.PT6H]: 6 * 60 * 60 * 1000,
  [Interval.P1D]: 24 * 60 * 60 * 1000,
  [Interval.P7D]: 7 * 60 * 60 * 1000,
  [Interval.P1M]: 30 * 60 * 60 * 1000,
  [Interval.P6M]: 182 * 60 * 60 * 1000,
  [Interval.YTD]: 364 * 60 * 60 * 1000
}

export function PricesWidgetD3({ market }: { market: Market }) {
  const [interval, setInterval] = useState<Interval | null>(Interval.PT6H)
  const [duration, setDuration] = useState<OHLCDuration>('P5M')
  const [ohlc, setOhlc] = useState<OHLC[]>([])
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null)

  const windowDimensions = useWindowDimensions()

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
      contents={
        <div className="min-h-[600px]">
          <div className="flex flex-row align-middle">
            <Title market={market} price={lastPrice()} delta={0.07} />
          </div>
          <div className="flex w-full place-items-center justify-between py-4 text-sm">
            <div className="text-left">
              <IntervalsDisplay
                selectedInterval={interval}
                selectedDuration={duration}
                onChange={(int: Interval) => {
                  setInterval(int)
                  setDuration(intervalToOHLCDuration[int])
                }}
              />
            </div>
            <div className="text-right text-xs text-darkBluishGray2">
              <LastUpdated lastUpdated={lastUpdated} />
            </div>
          </div>
          <div className="size-full min-h-[500px] p-4">
            <OHLCChart
              data={ohlc}
              interval={interval}
              intervalTouched={() => setInterval(null)}
            />
          </div>
        </div>
      }
    />
  )
}

interface OHLCChartProps {
  data: OHLC[]
  interval: Interval | null
  intervalTouched: () => void
}

function OHLCChart({ data, interval, intervalTouched }: OHLCChartProps) {
  const ref = useRef<SVGSVGElement>(null)
  const zoomRef = useRef(d3.zoomIdentity)
  const [xDomainMs, setXDomainMs] = useState<number>(6 * 60 * 60 * 1000)

  useEffect(() => {
    if (data.length === 0) return

    const width = ref.current ? ref.current.parentElement!.clientWidth : 760
    // const height = ref.current ? ref.current.parentElement!.clientHeight : 0
    //const width = 760
    const height = 500

    const margin = { top: 10, right: 40, bottom: 20, left: 40 }
    const innerWidth = width - margin.left - margin.right
    const innerHeight = height - margin.top - margin.bottom
    //const innerWidth = width
    //const innerHeight = height

    const svg = d3
      .select(ref.current)
      .attr('preserveAspectRatio', 'xMinYMin meet')
      .attr('width', innerWidth)
      .attr('height', innerHeight)
      .style('overflow', 'visible') // FIXME scales are outside of the svg viewport
      .classed('svg-content-responsive', true)

    const zoom = d3
      .zoom()
      .scaleExtent([-10, 10])
      /*.translateExtent([
        [0, 0],
        [width, height]
      ])
      .extent([
        [0, 0],
        [width, height]
      ])*/
      .on('zoom', (event) => {
        if (zoomRef.current.k != event.transform.k) {
          intervalTouched()
        }
        zoomRef.current = event.transform
        updateChart(event.transform.rescaleX(xScale), event.transform.k)
      })

    if (interval) {
      setXDomainMs(intervalToMs[interval])
    }

    const now = new Date()
    // Setting up scales
    const xScale = d3
      .scaleTime()
      //.domain(d3.extent(data, (d) => d.start))
      .domain([new Date(now.getTime() - xDomainMs), now])
      .range([0, innerWidth])

    const yScale = d3.scaleLinear().range([innerHeight, 0])

    const xAxis = d3.axisBottom(xScale)
    const yAxis = d3.axisRight(yScale) //.ticks(20)

    svg
      .append('g')
      .attr('class', 'x-axis text-darkBluishGray4 text-xs')
      .attr('transform', `translate(0,${innerHeight})`)

    svg.append('g').attr('class', 'y-axis text-darkBluishGray4 text-xs')
    //.attr('transform', `translate(${innerWidth},0)`)
    svg.select('.y-axis').attr('transform', `translate(${innerWidth},0)`)

    const updateChart = (newXScale, currentZoom) => {
      // Update axes
      svg.select('.x-axis').call(xAxis.scale(newXScale))

      const visibleData: OHLC[] = data.filter(
        (d) => newXScale(d.start) >= 0 && newXScale(d.start) <= innerWidth
      )
      yScale.domain([
        d3.min(visibleData, (d) => d.low),
        d3.max(visibleData, (d) => d.high)
      ])

      svg.select('.y-axis').call(yAxis.scale(yScale))

      const bars = svg
        .selectAll('.ohlc')
        .data(visibleData, (d) => (d as OHLC).start.toString())

      // remove all candles that are not in visible data
      bars.exit().remove()

      // add missing and merge existing
      const barsEnter = bars.enter().append('g').attr('class', 'ohlc')

      barsEnter
        .append('line')
        .attr('class', 'range')
        .merge(bars.select('.range'))
        .attr('x1', (d) => newXScale(d.start))
        .attr('x2', (d) => newXScale(d.start))
        .attr('y1', (d) => yScale(d.high))
        .attr('y2', (d) => yScale(d.low))
        .attr('stroke', (d) => (d.close > d.open ? '#39CF63' : '#FF5A50'))
        .attr('stroke-width', 1 * currentZoom)

      barsEnter
        .append('line')
        .attr('class', 'open-close')
        .merge(bars.select('.open-close'))
        .attr('x1', (d) => newXScale(d.start)) // Offset by 2 pixels to the left for open
        .attr('x2', (d) => newXScale(d.start)) // Offset by 2 pixels to the right for close
        .attr('y1', (d) => yScale(d.open))
        .attr('y2', (d) => yScale(d.close))
        .attr('stroke', (d) => (d.close > d.open ? '#39CF63' : '#FF5A50'))
        .attr('stroke-width', 6 * currentZoom)
    }

    // listen to zoom events, and then call transform first time to draw the graph
    svg.call(zoom).call(zoom.transform, zoomRef.current)

    // Initial draw
    //updateChart(xScale, 1)
  }, [data, ref.current?.parentElement?.clientWidth])

  return <svg ref={ref} />
}

function Title({
  market,
  price,
  delta
}: {
  market: Market
  price: string
  delta: number
}) {
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
      <div className="flex place-items-center gap-4 text-right">
        {price}
        <div className="text-sm">
          <DeltaDisplay delta={delta} />
        </div>
      </div>
    </div>
  )
}

function DeltaDisplay({ delta }: { delta: number }) {
  const getColor = (delta: number) => {
    if (delta > 0) return 'text-olhcGreen'
    if (delta < 0) return 'text-olhcRed'
    return 'text-darkBluishGray3'
  }

  // Format delta as a percentage with two decimal places
  const formattedDelta = `${delta >= 0 ? '+' : '-'}${(delta * 100).toFixed(2)}%`

  return <span className={getColor(delta)}>{formattedDelta}</span>
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
  selectedInterval: Interval | null
  selectedDuration: OHLCDuration
  onChange: (interval: Interval) => void
}) {
  // Convert the enum into an array of values for rendering
  const intervalValues = Object.values(Interval)

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
