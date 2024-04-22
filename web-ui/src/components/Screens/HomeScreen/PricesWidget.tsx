import { Widget } from 'components/common/Widget'
import { calculateTickSpacing } from 'utils/orderBookUtils'
import React, { Fragment, useEffect, useMemo, useState } from 'react'
import Spinner from 'components/common/Spinner'
import { Direction, OHLC, pricesTopic, Publishable } from 'websocketMessages'
import { mergeOHLC } from 'utils/pricesUtils'
import { useWebsocketSubscription } from 'contexts/websocket'
import { useWindowDimensions, widgetSize, WindowDimensions } from 'utils/layout'

type PriceParameters = {
  totalWidth: number
  totalHeight: number
  gridLines: number
  chartStartX: number
  chartEndX: number
  chartWidth: number
  chartStartY: number
  chartEndY: number
  chartHeight: number
  barWidth: number
  minPrice: number
  maxPrice: number
  priceRange: number
  tickRange: number
  gridSpacing: number
  ticks: number[]
}

type ZoomLevels = 'Week' | 'Day' | 'Hour'

function calculateParameters(
  ohlc: OHLC[],
  windowDimensions: WindowDimensions
): PriceParameters {
  const totalWidth = widgetSize(windowDimensions.width)
  const gridLines = 5
  const chartStartX = 20
  const chartEndX = totalWidth - 80
  const chartWidth = chartEndX - chartStartX
  const barWidth = Math.ceil(chartWidth / ohlc.length)
  const minPrice = Math.min(...ohlc.map((o) => o.low))
  const maxPrice = Math.max(...ohlc.map((o) => o.high))
  const priceRange = maxPrice - minPrice
  const gridSpacing = calculateTickSpacing(minPrice, maxPrice, gridLines)
  const totalHeight = 385
  const chartStartY = 20
  const chartEndY = totalHeight - 60

  const minTick = Math.floor(minPrice / gridSpacing) * gridSpacing

  const ticks: number[] = [minTick]
  while (ticks[ticks.length - 1] < maxPrice) {
    ticks.push(ticks[ticks.length - 1] + gridSpacing)
  }

  return {
    totalWidth,
    gridLines,
    chartStartX,
    chartEndX,
    chartWidth,
    chartStartY,
    chartEndY,
    chartHeight: chartEndY - chartStartY,
    barWidth,
    priceRange,
    minPrice,
    maxPrice,
    totalHeight,
    gridSpacing,
    ticks,
    tickRange: ticks[ticks.length - 1] - ticks[0]
  }
}

export function PricesWidget({ marketId }: { marketId: string }) {
  const [zoom, setZoom] = useState<ZoomLevels>('Week')
  const [latestStart, setLatestStart] = useState<Date | undefined>()
  const [ohlc, setOhlc] = useState<OHLC[]>([])
  const [params, setParams] = useState<PriceParameters>()
  const windowDimensions = useWindowDimensions()

  useWebsocketSubscription({
    topics: useMemo(() => [pricesTopic(marketId)], [marketId]),
    handler: (message: Publishable) => {
      if (message.type === 'Prices') {
        if (message.full) {
          setOhlc(message.ohlc)
        } else {
          setOhlc((o) => o.concat([...message.ohlc]))
        }
      }
    }
  })

  // Allow keyboard zoom and pan
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'ArrowLeft') {
        panLeft()
      } else if (event.key === 'ArrowRight') {
        panRight()
      } else if (event.key === '+') {
        zoomIn()
      } else if (event.key === '-') {
        zoomOut()
      }
    }

    document.addEventListener('keydown', handleKeyDown, false)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
    }
  })

  // the duration each candlestick should cover, based on the zoom level
  function ohlcDuration(zoom: ZoomLevels): number {
    if (zoom == 'Week') {
      return 1000 * 60 * 60 * 12
    } else if (zoom == 'Day') {
      return 1000 * 60 * 60 * 2
    } else {
      // Hour
      return 1000 * 60 * 5
    }
  }

  // the total time, in ms, that should be shown at once, based on the zoom level
  function visibleDurationMs(zoom: ZoomLevels): number {
    if (zoom == 'Week') {
      return 1000 * 60 * 60 * 24 * 7
    } else if (zoom == 'Day') {
      return 1000 * 60 * 60 * 24
    } else {
      // Hour
      return 1000 * 60 * 60
    }
  }

  const mergedOhlc = useMemo(() => {
    // filter to candlesticks which are in the visible range
    function visibleOhlc(
      latestStart: Date | undefined,
      zoom: ZoomLevels,
      ohlc: OHLC[]
    ): OHLC[] {
      if (ohlc.length > 0) {
        if (latestStart === undefined) {
          latestStart = ohlc[ohlc.length - 1].start
        }
        const earliestStart = new Date(
          latestStart.getTime() - visibleDurationMs(zoom)
        )
        return ohlc.filter((l) => {
          return l.start >= earliestStart && l.start < latestStart!
        })
      } else {
        return []
      }
    }

    const merged = mergeOHLC(
      visibleOhlc(latestStart, zoom, ohlc),
      ohlcDuration(zoom)
    )
    if (merged.length > 0) {
      setParams(calculateParameters(merged, windowDimensions))
    }
    return merged
  }, [latestStart, ohlc, zoom, windowDimensions])

  // draw the body of a candlestick, with some special treatment if it is marked as "incomplete"
  function drawCandle(params: PriceParameters, l: OHLC, i: number) {
    if (params) {
      return (
        <rect
          x={i * params.barWidth + params.chartStartX + 1}
          width={params.barWidth - 3}
          y={priceToY(params, Math.max(l.open, l.close))}
          height={
            (params.chartHeight * Math.abs(l.open - l.close)) / params.tickRange
          }
          fill={l.close > l.open ? '#10A327' : '#7F1D1D'}
          stroke={
            l.incomplete
              ? l.close > l.open
                ? '#10A327'
                : '#7F1D1D'
              : undefined
          }
          strokeDasharray={l.incomplete ? 2 : 0}
          strokeWidth={2}
          fillOpacity={l.incomplete ? 0.5 : 1}
        />
      )
    }
  }

  // draw a wick (either Up or Down)
  function drawWick(
    params: PriceParameters,
    l: OHLC,
    i: number,
    direction: Direction
  ) {
    if (params) {
      return (
        <line
          x1={
            i * params.barWidth + params.chartStartX + (params.barWidth - 4) / 2
          }
          x2={
            i * params.barWidth + params.chartStartX + (params.barWidth - 4) / 2
          }
          y1={priceToY(
            params,
            (direction == 'Up' ? Math.max : Math.min)(l.open, l.close)
          )}
          y2={priceToY(params, direction == 'Up' ? l.high : l.low)}
          stroke={l.close > l.open ? '#10A327' : '#7F1D1D'}
        />
      )
    }
  }

  // calculates the y position based on the price
  function priceToY(params: PriceParameters, price: number) {
    return (
      params.chartEndY -
      (params.chartHeight * (price - params.ticks[0])) / params.tickRange
    )
  }

  // label for use at the weekly zoom level (e.g. Mar 19)
  function weeklyLabel(date: Date): string {
    return (
      [
        'Jan',
        'Feb',
        'Mar',
        'Apr',
        'May',
        'Jun',
        'Jul',
        'Aug',
        'Sep',
        'Oct',
        'Nov',
        'Dec'
      ][date.getMonth()] +
      ' ' +
      date.getDate()
    )
  }

  // x-axis label, based on zoom level
  // the last label used is passed in to allow for special handling of day-crossings when zoomed in
  function calculateLabel(
    zoom: ZoomLevels,
    ohlc: OHLC,
    lastLabel?: string
  ): string {
    const date = ohlc.start
    if (zoom === 'Week') {
      return weeklyLabel(date)
    } else {
      const hours = date.getHours()
      const lastHours = lastLabel ? parseInt(lastLabel.slice(0, 2)) : 0
      if (hours < lastHours) {
        return weeklyLabel(date)
      } else {
        if (zoom === 'Day') {
          return (hours < 10 ? '0' + hours : hours) + ':00'
        } else {
          const minutes = date.getMinutes()
          return (
            date.getHours() + ':' + (minutes < 10 ? '0' + minutes : minutes)
          )
        }
      }
    }
  }

  // how far to pan left or right, based on zoom level
  function panDistance(zoom: ZoomLevels) {
    if (zoom === 'Day') {
      return 1000 * 60 * 60 * 2
    } else if (zoom === 'Hour') {
      return 1000 * 60 * 5 * 2
    } else if (zoom === 'Week') {
      return 1000 * 60 * 60 * 24 * 7
    } else {
      return 0
    }
  }

  // draw the grid columns and x-axis labels
  function drawGridX(params: PriceParameters, ohlc: OHLC[]) {
    let lastLabel: string | undefined
    return (
      <>
        {ohlc.map((l, i) => {
          const label = calculateLabel(zoom, l, lastLabel)
          const oldLastLabel = lastLabel
          lastLabel = label
          const x = params.chartStartX + i * params.barWidth
          return (
            <Fragment key={l.start.getTime()}>
              {i % 2 == 0 && (
                <line
                  y1={params.chartStartY}
                  y2={params.chartEndY}
                  x1={x}
                  x2={x}
                  stroke="white"
                  strokeOpacity={0.7}
                  strokeDasharray={0}
                />
              )}
              {label != oldLastLabel && (
                <text
                  x={x}
                  y={params.chartEndY + 12}
                  fill={'white'}
                  transform={`rotate(45,${x},${params.chartEndY + 12})`}
                >
                  {label}
                </text>
              )}
            </Fragment>
          )
        })}
        <line
          y1={params.chartStartY}
          y2={params.chartEndY}
          x1={params.chartEndX + 4}
          x2={params.chartEndX + 4}
          stroke="white"
          strokeOpacity={0.7}
          strokeDasharray={0}
        />
      </>
    )
  }

  // draw the grid rows and y-axis labels
  function drawGridY(params: PriceParameters) {
    return (
      <>
        {params.ticks.map((t) => {
          const y = priceToY(params, t)
          return (
            <Fragment key={t}>
              <line
                x1={params.chartStartX}
                x2={params.chartEndX + 4}
                y1={y}
                y2={y}
                stroke="white"
                strokeOpacity={0.7}
              />
              <text x={params.chartEndX + 12} y={y + 5} fill={'white'}>
                {
                  // dynamically adjust the number of decimals based on the grid spacing
                  t.toFixed(-Math.floor(Math.log10(params.gridSpacing)))
                }
              </text>
            </Fragment>
          )
        })}
      </>
    )
  }

  // prevent panning left in weekly zoom or if there are fewer than 10 panDistances left
  function panLeftAllowed() {
    if (zoom === 'Week') {
      return false
    }
    return (
      !latestStart ||
      latestStart >=
        new Date(ohlc[0]?.start?.getTime() + panDistance(zoom) * 10)
    )
  }

  // prevent panning right in weekly zoom or if there's no more data
  function panRightAllowed() {
    if (zoom === 'Week') {
      return false
    }
    return (
      latestStart &&
      latestStart <
        new Date(ohlc[ohlc.length - 1]?.start?.getTime() - panDistance(zoom))
    )
  }

  function panLeft() {
    if (panLeftAllowed()) {
      setLatestStart(
        new Date(
          (latestStart ? latestStart : ohlc[ohlc.length - 1]!.start).getTime() -
            panDistance(zoom)
        )
      )
    }
  }

  function panRight() {
    if (panRightAllowed()) {
      setLatestStart(
        new Date(
          (latestStart ? latestStart : ohlc[ohlc.length - 1]!.start).getTime() +
            panDistance(zoom)
        )
      )
    }
  }

  function zoomIn() {
    setZoom(zoom === 'Day' ? 'Hour' : zoom === 'Week' ? 'Day' : 'Hour')
  }

  function zoomOut() {
    setZoom(zoom == 'Day' ? 'Week' : zoom === 'Hour' ? 'Day' : 'Week')
    if (zoom === 'Week') {
      setLatestStart(undefined)
    }
  }

  // compute the title showing the date range being displayed
  function title(): string {
    const firstDate = mergedOhlc[0]?.start
    const lastDate = mergedOhlc[mergedOhlc.length - 1]?.start
    let startDate = firstDate
    if (latestStart) {
      startDate = new Date(
        Math.max(
          firstDate.getTime(),
          latestStart.getTime() - visibleDurationMs(zoom)
        )
      )
    }
    const endDate = new Date(
      latestStart
        ? latestStart.getTime() + ohlcDuration(zoom)
        : lastDate.getTime() + mergedOhlc[mergedOhlc.length - 1].durationMs
    )
    if (endDate.getDate() == startDate.getDate()) {
      return `${startDate.toLocaleDateString()}, ${startDate.toLocaleTimeString()} to ${endDate.toLocaleTimeString()}`
    } else {
      return `${startDate.toLocaleDateString()} to ${endDate.toLocaleDateString()}`
    }
  }

  return (
    <Widget
      title={'Prices'}
      contents={
        <div>
          <div className="flex flex-row align-middle">
            <div className="shrink-0">
              <button
                className="px-1 text-xl disabled:opacity-50"
                disabled={zoom == 'Hour'}
                onClick={zoomIn}
              >
                +
              </button>
              <button
                className="px-1 text-xl disabled:opacity-50"
                disabled={zoom == 'Week'}
                onClick={zoomOut}
              >
                -
              </button>
              <button
                className="px-1 text-xl disabled:opacity-50"
                disabled={!panLeftAllowed()}
                onClick={() => panLeft()}
              >
                ←
              </button>
              <button
                className="px-1 text-xl disabled:opacity-50"
                disabled={!panRightAllowed()}
                onClick={() => panRight()}
              >
                →
              </button>
            </div>
            <div className="flex w-full justify-around align-middle">
              {mergedOhlc.length > 0 && params && title()}
            </div>
          </div>
          {mergedOhlc.length > 0 && params ? (
            <svg width={params.totalWidth} height={params.totalHeight}>
              {drawGridY(params)}
              {drawGridX(params, mergedOhlc)}
              {mergedOhlc.map((l, i) => (
                <Fragment key={`${l.start}`}>
                  {drawCandle(params, l, i)}
                  {drawWick(params, l, i, 'Up')}
                  {drawWick(params, l, i, 'Down')}
                </Fragment>
              ))}
            </svg>
          ) : (
            <Spinner />
          )}
        </div>
      }
    />
  )
}
