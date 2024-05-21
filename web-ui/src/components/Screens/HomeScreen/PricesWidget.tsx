import { Widget } from 'components/common/Widget'
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  OHLC,
  OHLCDuration,
  OHLCDurationSchema,
  pricesTopic,
  Publishable
} from 'websocketMessages'
import { mergeOHLC, ohlcDurationsMs } from 'utils/pricesUtils'
import { useWebsocketSubscription } from 'contexts/websocket'
import { produce } from 'immer'
import * as d3 from 'd3'

import { Market } from 'markets'
import SymbolIcon from 'components/common/SymbolIcon'
import { classNames } from 'utils'
import { useMeasure } from 'react-use'
import { useGesture } from '@use-gesture/react'

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

function offsetInterval(date: Date, interval: PricesInterval): Date {
  switch (interval) {
    case PricesInterval.YTD:
      return d3.timeYear.floor(new Date())
    case PricesInterval.PT1H:
      return d3.timeHour.offset(date, -1)
    case PricesInterval.PT6H:
      return d3.timeHour.offset(date, -6)
    case PricesInterval.P1D:
      return d3.timeDay.offset(date, -1)
    case PricesInterval.P7D:
      return d3.timeDay.offset(date, -7)
    case PricesInterval.P1M:
      return d3.timeMonth.offset(date, -1)
    case PricesInterval.P6M:
      return d3.timeMonth.offset(date, -6)
  }
}

function floorToDurationStart(date: Date, duration: OHLCDuration): Date {
  switch (duration) {
    case 'P1M':
      return d3.timeMinute.every(1)!.floor(date)
    case 'P5M':
      return d3.timeMinute.every(5)!.floor(date)
    case 'P15M':
      return d3.timeMinute.every(15)!.floor(date)
    case 'P1H':
      return d3.timeHour.every(1)!.floor(date)
    case 'P4H':
      return d3.timeHour.every(4)!.floor(date)
    case 'P1D':
      return d3.timeDay.every(1)!.floor(date)
  }
}

function halfOhlcDurationMs(ohlcDuration: OHLCDuration): number {
  return ohlcDurationsMs[ohlcDuration] / 2
}

function shorterDurationOrNull(duration: OHLCDuration): OHLCDuration | null {
  const durations: OHLCDuration[] = OHLCDurationSchema.options
  const currentIndex = durations.indexOf(duration)
  return currentIndex > 0 ? durations[currentIndex - 1] : null
}

function longerDurationOrNull(duration: OHLCDuration): OHLCDuration | null {
  const durations: OHLCDuration[] = OHLCDurationSchema.options
  const currentIndex = durations.indexOf(duration)
  return currentIndex < durations.length - 1
    ? durations[currentIndex + 1]
    : null
}

export function PricesWidget({ market }: { market: Market }) {
  const [ref, { width }] = useMeasure()

  const [interval, setInterval] = useState<PricesInterval | null>(
    PricesInterval.PT6H
  )
  const [duration, setDuration] = useState<OHLCDuration>('P5M')
  const [ohlc, setOhlc] = useState<OHLC[]>([])
  const [ohlcLoading, setOhlcLoading] = useState<boolean>(true)
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
          setOhlcLoading(false)
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
                    setOhlcLoading(true)
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
              disabled={ohlcLoading}
              ohlc={ohlc}
              lastPrice={lastPrice()}
              params={{
                width: Math.max(width - 40, 0), // paddings
                height: 500,
                interval: interval,
                resetInterval: () => setInterval(null),
                duration: duration,
                shorterDuration: shorterDurationOrNull(duration),
                longerDuration: longerDurationOrNull(duration),
                requestDurationChange: (newDuration) => {
                  if (duration != newDuration) {
                    setDuration(newDuration)
                    setOhlcLoading(true)
                  }
                }
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
  resetInterval: () => void
  duration: OHLCDuration
  shorterDuration: OHLCDuration | null
  longerDuration: OHLCDuration | null
  requestDurationChange: (duration: OHLCDuration) => void
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

  const domainXRef = useRef<[Date, Date]>([
    offsetInterval(new Date(), PricesInterval.PT6H),
    d3.timeMillisecond.offset(
      floorToDurationStart(new Date(), params.duration),
      halfOhlcDurationMs(params.duration)
    )
  ])
  const xScale = useMemo(
    () => d3.scaleTime().domain(domainXRef.current).range([0, innerWidth]),
    [domainXRef, innerWidth]
  )
  const xAxis = useMemo(
    () =>
      d3
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
        }),
    [innerHeight, xScale]
  )

  const yScale = useMemo(
    () => d3.scaleLinear().range([innerHeight, 0]),
    [innerHeight]
  )
  const yAxis = useMemo(
    () =>
      d3
        .axisRight(yScale)
        .tickSize(0)
        .tickFormat((x: d3.NumberValue) => x.valueOf().toFixed(2))
        .tickPadding(5),
    [yScale]
  )
  // grid is separated from the axis to let ohlc candles be rendered above the grid, but below the axis to slide under ticks
  const yAxisGrid = useMemo(
    () => d3.axisRight(yScale).tickSizeOuter(0).tickSizeInner(-innerWidth),
    [yScale, innerWidth]
  )

  const [autoPanRight, setAutoPanRight] = useState(true)

  const drawChart = useCallback(
    (newXScale: d3.ScaleTime<number, number, never>) => {
      // calculate visible range
      const visibleData: OHLC[] = ohlc.filter((d) => {
        return (
          // add some pixels to let bars slide outside of viewport before disappearing
          newXScale(d.start) >= -50 && newXScale(d.start) <= innerWidth + 50
        )
      })

      // scale x-axis
      // @ts-expect-error @definitelytyped/no-unnecessary-generics
      svg.select('.x-axis').call(xAxis.scale(newXScale))

      // scale y-axis
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
    },
    [innerWidth, lastPrice, ohlc, svg, xAxis, yAxis, yAxisGrid, yScale]
  )

  useEffect(() => {
    if (params.interval) {
      // calculate new domain for the selected interval
      domainXRef.current = [
        offsetInterval(new Date(), params.interval),
        d3.timeMillisecond.offset(
          floorToDurationStart(new Date(), params.duration),
          halfOhlcDurationMs(params.duration)
        )
      ]
      xScale.domain(domainXRef.current)
      drawChart(xScale)
      setAutoPanRight(true)
    }
  }, [params.interval, params.duration, xScale, drawChart])

  useEffect(() => {
    // roll domain forward on new candle
    if (autoPanRight && ohlc && ohlc.length > 0) {
      const currentBeginOfTheWorldTime = domainXRef.current[0].getTime()
      const currentEndOfTheWorldTime = domainXRef.current[1].getTime()
      const newEndOfTheWorldTime = d3.timeMillisecond
        .offset(
          floorToDurationStart(new Date(), params.duration),
          halfOhlcDurationMs(params.duration)
        )
        .getTime()
      const autoPanTime = newEndOfTheWorldTime - currentEndOfTheWorldTime

      const newDomain: [Date, Date] = [
        new Date(currentBeginOfTheWorldTime + autoPanTime),
        new Date(currentEndOfTheWorldTime + autoPanTime)
      ]

      domainXRef.current = newDomain
      drawChart(xScale.domain(newDomain))
    }
  }, [ohlc, autoPanRight, params.duration, xScale, drawChart])

  function updateMouseProjections(mouseX: number, mouseY: number) {
    svg
      .select('.x-axis-mouse-projection')
      .classed('hidden', mouseX > innerWidth)
      .attr('transform', `translate(${mouseX},0)`)
      .select('text')
      .text(d3.timeFormat(`%_d %b %y %H:%M`)(xScale.invert(mouseX)))

    svg
      .select('.y-axis-mouse-projection')
      .classed('hidden', mouseY > innerHeight)
      .attr('transform', `translate(0,${mouseY})`)
      .select('text')
      .text(yScale.invert(mouseY).toFixed(2))
  }

  function hideMouseProjections() {
    svg.select('.x-axis-mouse-projection').classed('hidden', true)
    svg.select('.y-axis-mouse-projection').classed('hidden', true)
  }

  function processPanAndZoom(deltaPan: number, deltaZoom: number) {
    const maxVisibleOHLCSlots = 200
    const minVisibleOHLCSlots = 20

    // domain data before drag initiated
    const domainXStartTime = domainXRef.current[0].getTime()
    const domainXEndTime = domainXRef.current[1].getTime()
    const domainXIntervalTime = domainXEndTime - domainXStartTime

    // pan duration
    const draggedXTime =
      xScale.invert(deltaPan).getTime() - xScale.invert(0).getTime()

    const candlestickSlotsVisible =
      domainXIntervalTime / ohlcDurationsMs[params.duration]

    // check if more/less granular OHLC sticks should be requested
    if (
      candlestickSlotsVisible <= minVisibleOHLCSlots &&
      params.shorterDuration
    ) {
      params.requestDurationChange(params.shorterDuration)
    } else if (
      candlestickSlotsVisible >= maxVisibleOHLCSlots &&
      params.longerDuration
    ) {
      params.requestDurationChange(params.longerDuration)
    }

    // calculate zoom level
    const zoomXFactor =
      (candlestickSlotsVisible < minVisibleOHLCSlots && deltaZoom > 0) ||
      (candlestickSlotsVisible > maxVisibleOHLCSlots && deltaZoom < 0)
        ? 0 // block zoom until shorter/longer candles are loaded (if available)
        : (2 * deltaZoom) / params.height

    // domain data with applied pan & zoom
    const newDomainXStartTime =
      domainXStartTime + domainXIntervalTime * zoomXFactor - draggedXTime
    const newDomainXEndTime = domainXEndTime - draggedXTime

    // limit panning by calculating offset to last visible ohlc stick
    const endOfTheWorldTime =
      ohlc.length > 0
        ? ohlc[ohlc.length - 1].start.getTime() +
          halfOhlcDurationMs(params.duration)
        : 0
    const limitPanOffset =
      newDomainXEndTime > endOfTheWorldTime
        ? newDomainXEndTime - endOfTheWorldTime
        : 0

    // apply domain changes and redraw chart
    const newDomain: [Date, Date] = [
      new Date(newDomainXStartTime - limitPanOffset),
      new Date(newDomainXEndTime - limitPanOffset)
    ]
    domainXRef.current = newDomain
    drawChart(xScale.domain(newDomain))

    // set auto-pan mode enabled in the rightmost point
    setAutoPanRight(
      endOfTheWorldTime - newDomainXEndTime <=
        halfOhlcDurationMs(params.duration) // leeway for disabling auto-pan
    )

    // reset selected interval on update of zoom level
    if (zoomXFactor != 1) {
      params.resetInterval()
    }
  }

  // blocking overlay area
  svg
    .select('.svg-disabled-overlay')
    .classed('hidden', !disabled)
    .classed('block', disabled)

  // Add gesture handling
  const gestureBindings = useGesture(
    {
      onDrag: ({ delta: [dx, dy] }) => {
        processPanAndZoom(dx, dy)
      },
      onPinch: ({ da: [distance], origin: [ox], memo = [distance, ox] }) => {
        // distance change is zoom
        const deltaZoom = distance - memo[0]
        // pinch center x coordinate change is pan
        const deltaPan = ox - memo[1]

        processPanAndZoom(deltaPan, deltaZoom)

        return [distance, ox]
      },
      onMove: ({ xy: [clientX, clientY] }) => {
        if (ref.current) {
          const svgRect = ref.current.getBoundingClientRect()
          const mouseX = clientX - svgRect.left - 0.8
          const mouseY = clientY - svgRect.top + 0.3
          updateMouseProjections(mouseX, mouseY)
        }
      },
      onHover: ({ hovering }) => {
        if (!hovering) hideMouseProjections()
      }
    },
    {
      pinch: {
        threshold: 0.1
      },
      drag: {
        filterTaps: true
      }
    }
  )

  drawChart(xScale)

  return (
    <svg
      ref={ref}
      width={params.width}
      height={params.height}
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
            width={margin.right + 5}
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
            x={innerWidth - 1}
            y="-9"
            width={margin.right}
            height="18"
            rx="3"
          />
          <text transform={`translate(${innerWidth + 3},4)`} />
        </g>
        <g className="y-axis-mouse-projection hidden text-xs">
          <line x1="0" x2={innerWidth} y1="0" y2="0" />
          <rect
            x={innerWidth - 1}
            y="-10"
            width={margin.right}
            height="20"
            rx="3"
          />
          <text transform={`translate(${innerWidth + 3},4)`} />
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
          <DailyChangeDisplay dailyChange={dailyChange} />
        </div>
      </div>
    </div>
  )
}

function DailyChangeDisplay({ dailyChange }: { dailyChange: number | null }) {
  const getColor = (dailyChange: number) => {
    if (dailyChange > 0) return 'text-olhcGreen'
    if (dailyChange < 0) return 'text-olhcRed'
    return 'text-darkBluishGray3'
  }

  if (dailyChange) {
    const formattedDailyChange = `${dailyChange >= 0 ? '+' : ''}${(
      dailyChange * 100
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

    return `Last updated ${year}/${month}/${day} ${formattedHours}:${minutes} ${amPm}`
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
