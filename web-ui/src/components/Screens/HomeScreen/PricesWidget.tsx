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

export function PricesWidget({ market }: { market: Market }) {
  const [ref, { width }] = useMeasure()

  const [interval, setInterval] = useState<PricesInterval | null>(
    PricesInterval.PT6H
  )
  const [duration, setDuration] = useState<OHLCDuration>('P5M')
  const [ohlc, setOhlc] = useState<OHLC[]>([])
  const [ohlcLoaded, setOhlcLoaded] = useState<boolean>(false)
  const [dailyChange, setDailyChange] = useState<number | null>(null)
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
            setDailyChange(message.dailyChange)
          }
          setLastUpdated(new Date())
          setOhlcLoaded(true)
        }
      },
      [duration]
    )
  })

  function lastPrice(): number | null {
    if (ohlc.length > 0) {
      return ohlc[ohlc.length - 1].close
    } else {
      return null
    }
  }

  return (
    <Widget
      id="prices"
      wrapperRef={ref}
      contents={
        <div className="min-h-[600px]">
          <div className="flex flex-row align-middle">
            <Title
              market={market}
              price={lastPrice()}
              dailyChange={dailyChange}
            />
          </div>
          <div className="flex w-full place-items-center justify-between py-4 text-sm">
            <div className="text-left">
              <IntervalsDisplay
                selectedInterval={interval}
                onChange={(int: PricesInterval) => {
                  const newDuration = intervalToOHLCDuration[int]
                  if (duration != newDuration) {
                    setDuration(newDuration)
                    setOhlcLoaded(false)
                  }
                  setInterval(int)
                }}
              />
            </div>
            <div className="hidden text-right text-xs text-darkBluishGray2 narrow:block">
              <LastUpdated lastUpdated={lastUpdated} />
            </div>
          </div>
          <div className="block text-xs text-darkBluishGray2 narrow:hidden">
            <LastUpdated lastUpdated={lastUpdated} />
          </div>
          <div className="size-full min-h-[500px] pl-2 pt-4">
            <OHLCChart
              disabled={!ohlcLoaded}
              ohlc={ohlc}
              lastPrice={lastPrice()}
              params={{
                width: Math.max(width - 40, 0), // paddings
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
  disabled,
  params,
  ohlc,
  lastPrice
}: {
  disabled: boolean
  params: PricesParameters
  ohlc: OHLC[]
  lastPrice: number | null
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

  const margin = {
    top: 0,
    bottom: 18,
    left: 0,
    // calculate 8 pixels for every price digit
    right: lastPrice ? lastPrice.toFixed(2).length * 8 : 30
  }
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
    .ticks(5)
    .tickFormat((d) => {
      const date = d instanceof Date ? d : new Date(d.valueOf())
      if (d3.timeHour(date) < date) {
        return d3.timeFormat('%H:%M')(date) // 24-hour clock [00,23] + minute [00,59]
      }
      if (d3.timeDay(date) < date) {
        return d3.timeFormat('%H:%M')(date) // 24-hour clock [00,23] + minute [00,59]
      }
      if (d3.timeMonth(date) < date) {
        return d3.timeFormat('%b %-d')(date) // abbreviated month name + day of the month without padding
      }
      if (d3.timeYear(date) < date) {
        return d3.timeFormat('%B')(date) // full month name
      }
      return d3.timeFormat('%Y')(date) // year with century
    })

  const yScale = d3.scaleLinear().range([innerHeight, 0])
  const yAxis = d3
    .axisRight(yScale)
    .tickSize(0)
    .tickFormat((x: d3.NumberValue) => x.valueOf().toFixed(2))
  const yAxisGrid = d3
    .axisRight(yScale)
    .tickSizeOuter(0)
    .tickSizeInner(-innerWidth)

  function updateMouseProjections(mouseX: number, mouseY: number) {
    svg
      .select('.x-axis-mouse-projection')
      .classed('hidden', mouseX > innerWidth)
      .attr('transform', `translate(${mouseX},0)`)
      .select('text')
      .text(
        d3.timeFormat(`%_d %b %y %H:%M`)(
          zoomRef.current.rescaleX(xScale).invert(mouseX)
        )
      )

    svg
      .select('.y-axis-mouse-projection')
      .classed('hidden', false)
      .attr('transform', `translate(0,${mouseY})`)
      .select('text')
      .text(yScale.invert(mouseY).toFixed(2))
  }

  const handleMouseMove = (event: MouseEvent) => {
    const [mouseX, mouseY] = d3.pointer(event)
    updateMouseProjections(mouseX, mouseY)
  }
  const handleMouseLeave = () => {
    svg.select('.x-axis-mouse-projection').classed('hidden', true)
    svg.select('.y-axis-mouse-projection').classed('hidden', true)
  }

  svg
    .on('mousemove', handleMouseMove)
    .on('mouseup', handleMouseMove)
    .on('mouseleave', handleMouseLeave)

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
        // add some pixels to let bars slide outside of viewport before disappearing
        newXScale(d.start) >= -25 && newXScale(d.start) <= innerWidth + 25
      )
    })
    if (visibleData.length == 0) return

    // scale x-axis
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.x-axis').call(xAxis.scale(newXScale))

    // scale y axis
    const yMin = d3.min(visibleData, (d) => d.low)
    const yMax = d3.max(visibleData, (d) => d.high)
    if (yMin && yMax) {
      yScale.domain([yMin * 0.995, yMax * 1.005])
    }
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.y-axis').call(yAxis.scale(yScale))
    // @ts-expect-error @definitelytyped/no-unnecessary-generics
    svg.select('.y-axis-grid').call(yAxisGrid.scale(yScale))

    // update current price tracker
    svg
      .select('.y-axis-current-price')
      .classed('hidden', !lastPrice)
      .transition()
      .duration(150)
      .attr('transform', `translate(0,${lastPrice ? yScale(lastPrice) : 0})`)
      .select('text')
      .text(lastPrice ? lastPrice.toFixed(2) : '')

    // calculate candle width
    const candleWidth =
      ohlc.length >= 2
        ? Math.abs(newXScale(ohlc[1].start) - newXScale(ohlc[0].start)) * 0.6
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
      .attr('fill', (d) => (d.close >= d.open ? '#39CF63' : '#FF5A50'))

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
  svg.select('.svg-main').call(zoom).call(zoom.transform, zoomRef.current)

  // blocking overlay area
  svg
    .select('.svg-disabled-overlay')
    .classed('hidden', !disabled)
    .classed('block', disabled)

  return (
    <svg
      ref={ref}
      width={params.width}
      height={params.height}
      style={{
        overflow: 'hidden'
      }}
    >
      <g className="svg-main cursor-crosshair">
        <rect
          className="opacity-0"
          x="0"
          y="0"
          width={params.width}
          height={params.height}
        />
        <g
          className="x-axis text-xs text-darkBluishGray4"
          transform={`translate(0,${innerHeight})`}
        />
        <g className="y-axis-grid" transform={`translate(${innerWidth},0)`} />
        <g className="y-axis-ohlc" />
        <g className="y-axis-bg">
          <rect
            x={innerWidth}
            y="0"
            width={margin.right}
            height={innerHeight}
          />
        </g>
        <g
          className="y-axis text-xs text-darkBluishGray4"
          transform={`translate(${innerWidth},0)`}
        />
        <g className="y-axis-current-price hidden text-xs">
          <line x1="0" x2={innerWidth} y1="0" y2="0" />
          <rect
            x={innerWidth - 3}
            y="-9"
            width={margin.right + 3}
            height="18"
            rx="3"
          />
          <text transform={`translate(${innerWidth + 1},4)`} />
        </g>
        <g className="y-axis-mouse-projection hidden text-xs">
          <line x1="0" x2={innerWidth} y1="0" y2="0" />
          <rect
            x={innerWidth - 3}
            y="-10"
            width={margin.right + 3}
            height="20"
            rx="3"
          />
          <text transform={`translate(${innerWidth + 1},4)`} />
        </g>
        <g className="x-axis-mouse-projection hidden text-xs">
          <line x1="0" x2="0" y1="0" y2={innerHeight} />
          <rect x="-50" y={innerHeight - 2} width="100" height="18" rx="3" />
          <text
            className="whitespace-pre"
            transform={`translate(-45,${innerHeight + 11})`}
          />
        </g>
      </g>
      <g className="svg-disabled-overlay">
        <rect x="0" y="0" width={params.width} height={params.height} />
      </g>
    </svg>
  )
}

function formatPrice(market: Market, price: number | null) {
  return price ? price.toFixed(market.tickSize.decimalPlaces() + 1) : ''
}

function Title({
  market,
  price,
  dailyChange
}: {
  market: Market
  price: number | null
  dailyChange: number | null
}) {
  return (
    <div className="flex w-full justify-between text-xl font-semibold">
      <div className="place-items-center text-left">
        <SymbolIcon
          symbol={market.baseSymbol.name}
          className="relative left-1 inline-block size-7"
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
        {formatPrice(market, price)}
        <div className="text-sm text-darkBluishGray2">
          <DailyChangeDisplay price={price} dailyChange={dailyChange} />
        </div>
      </div>
    </div>
  )
}

function DailyChangeDisplay({
  price,
  dailyChange
}: {
  price: number | null
  dailyChange: number | null
}) {
  const getColor = (dailyChange: number) => {
    if (dailyChange > 0) return 'text-olhcGreen'
    if (dailyChange < 0) return 'text-olhcRed'
    return 'text-darkBluishGray3'
  }

  if (price && dailyChange) {
    const formattedDailyChange = `${dailyChange >= 0 ? '+' : ''}${(
      (dailyChange / price) *
      100
    ).toFixed(2)}%`
    return (
      <div className="flex place-items-center gap-2">
        <div className={getColor(dailyChange)}>{formattedDailyChange}</div>
        <div className="text-xs">1d</div>
      </div>
    )
  } else {
    return <></>
  }
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
  onChange
}: {
  selectedInterval: PricesInterval | null
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
    </div>
  )
}
