import { Widget } from 'components/common/Widget'
import { calculateTickSpacing } from 'utils/orderBookUtils'
import React, {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useState
} from 'react'
import Spinner from 'components/common/Spinner'
import {
  Direction,
  OHLC,
  OHLCDuration,
  OHLCDurationSchema,
  pricesTopic,
  Publishable
} from 'websocketMessages'
import { mergeOHLC, olhcDurationsMs } from 'utils/pricesUtils'
import { useWebsocketSubscription } from 'contexts/websocket'
import { useWindowDimensions, widgetSize, WindowDimensions } from 'utils/layout'
import { produce } from 'immer'

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

type ViewPort = {
  earliestStart: Date | undefined
  latestStart: Date | undefined
}

function calculateParameters(
  ohlc: OHLC[],
  windowDimensions: WindowDimensions
): PriceParameters {
  const totalWidth = widgetSize(windowDimensions.width)
  const gridLines = 5
  const chartStartX = 20
  const chartEndX = totalWidth - 80
  const chartWidth = chartEndX - chartStartX
  const barWidth = Math.floor(chartWidth / ohlc.length)
  const minPrice = Math.min(...ohlc.map((o) => o.low))
  const maxPrice = Math.max(...ohlc.map((o) => o.high))
  const priceRange = maxPrice - minPrice
  const gridSpacing = Math.max(
    calculateTickSpacing(minPrice, maxPrice, gridLines),
    0.05
  )
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
  const [duration, setDuration] = useState<OHLCDuration>('P5M')
  const [viewPort, setViewPort] = useState<ViewPort>({
    earliestStart: undefined,
    latestStart: undefined
  })
  const [ohlc, setOhlc] = useState<OHLC[]>([])
  const [params, setParams] = useState<PriceParameters>()
  const windowDimensions = useWindowDimensions()
  const maxCandles = 20

  useWebsocketSubscription({
    topics: useMemo(
      () => [pricesTopic(marketId, duration)],
      [marketId, duration]
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
        }
      },
      [duration]
    )
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

  const viewportOhlc = useMemo(() => {
    // filter to candlesticks which are in the visible range
    function filterVisibleOhlc(viewPort: ViewPort, ohlc: OHLC[]): OHLC[] {
      if (ohlc.length > 0) {
        if (viewPort.earliestStart === undefined) {
          setViewPort({
            earliestStart:
              ohlc[Math.max(ohlc.length - 1 - maxCandles, 0)].start,
            latestStart: viewPort.latestStart
          })
          return []
        }

        const latestStart = viewPort.latestStart
          ? viewPort.latestStart
          : ohlc[ohlc.length - 1].start

        return ohlc.filter((l) => {
          return l.start >= viewPort.earliestStart! && l.start <= latestStart!
        })
      } else {
        return []
      }
    }

    const visibleOhlc = filterVisibleOhlc(viewPort, ohlc)

    if (visibleOhlc.length > 0) {
      setParams(calculateParameters(visibleOhlc, windowDimensions))
    }

    return visibleOhlc
  }, [ohlc, viewPort, windowDimensions])

  // draw the body of a candlestick, with some special treatment if it is marked as "incomplete"
  function drawCandle(params: PriceParameters, l: OHLC, i: number) {
    if (params) {
      return (
        <rect
          x={i * params.barWidth + params.chartStartX + 5}
          width={params.barWidth - 3}
          y={priceToY(params, Math.max(l.open, l.close))}
          height={
            (params.chartHeight *
              Math.max(
                Math.abs(l.open - l.close),
                params.gridSpacing * 0.005
              )) /
            params.tickRange
          }
          fill={l.close < l.open ? '#7F1D1D' : '#10A327'}
          stroke={
            l.incomplete
              ? l.close < l.open
                ? '#7F1D1D'
                : '#10A327'
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
            i * params.barWidth +
            5 +
            params.chartStartX +
            (params.barWidth - 4) / 2
          }
          x2={
            i * params.barWidth +
            5 +
            params.chartStartX +
            (params.barWidth - 4) / 2
          }
          y1={priceToY(
            params,
            (direction == 'Up' ? Math.max : Math.min)(l.open, l.close)
          )}
          y2={priceToY(params, direction == 'Up' ? l.high : l.low)}
          stroke={l.close < l.open ? '#7F1D1D' : '#10A327'}
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
    ohlcDuration: OHLCDuration,
    ohlc: OHLC,
    lastLabel?: string
  ): string {
    const date = ohlc.start
    if (ohlcDuration === 'P1D') {
      return weeklyLabel(date)
    } else {
      const hours = date.getHours()
      const lastHours = lastLabel ? parseInt(lastLabel.slice(0, 2)) : 0
      if (hours < lastHours) {
        return weeklyLabel(date)
      } else {
        if (ohlcDuration === 'P1H' || ohlcDuration === 'P4H') {
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

  // draw the grid columns and x-axis labels
  function drawGridX(params: PriceParameters, ohlc: OHLC[]) {
    let lastLabel: string | undefined
    return (
      <>
        {ohlc.map((l, i) => {
          const label = calculateLabel(duration, l, lastLabel)
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
                  t.toFixed(
                    Math.max(0, -Math.floor(Math.log10(params.gridSpacing)))
                  )
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
    return !viewPort.earliestStart || viewPort.earliestStart > ohlc[0]?.start
  }

  // prevent panning right in weekly zoom or if there's no more data
  function panRightAllowed() {
    return (
      viewPort.latestStart &&
      viewPort.latestStart < ohlc[ohlc.length - 1]?.start
    )
  }

  function panLeft() {
    if (panLeftAllowed()) {
      const earliestIndex = ohlc.findIndex(
        (ohlc) => ohlc.start === viewPort.earliestStart
      )
      const newEarliestIndex = Math.max(earliestIndex - maxCandles, 0)
      const newLatestIndex = Math.min(
        newEarliestIndex + maxCandles,
        ohlc.length - 1
      )

      setViewPort({
        earliestStart: ohlc[newEarliestIndex].start,
        latestStart: ohlc[newLatestIndex].start
      })
    }
  }

  function panRight() {
    if (panRightAllowed()) {
      const latestIndex = ohlc.findIndex(
        (ohlc) => ohlc.start === viewPort.latestStart
      )
      const newLatestIndex = Math.min(latestIndex + maxCandles, ohlc.length - 1)
      const newEarliestIndex = Math.max(newLatestIndex - maxCandles, 0)

      setViewPort({
        earliestStart: ohlc[newEarliestIndex].start,
        latestStart: ohlc[newLatestIndex].start
      })
    }
  }

  function zoomIn() {
    const values = Object.values(OHLCDurationSchema.Values)
    const idx = values.indexOf(duration)
    const inner = idx > 0 ? values[idx - 1] : duration
    setDuration(inner)
    setOhlc([])
    setViewPort({
      earliestStart: undefined,
      latestStart: undefined
    })
  }

  function zoomOut() {
    const values = Object.values(OHLCDurationSchema.Values)
    const idx = values.indexOf(duration)
    const outer = idx < values.length - 1 ? values[idx + 1] : duration
    setDuration(outer)
    setOhlc([])
    setViewPort({
      earliestStart: undefined,
      latestStart: undefined
    })
  }

  function canZoomOut(duration: OHLCDuration): boolean {
    const values = Object.values(OHLCDurationSchema.Values)
    const idx = values.indexOf(duration)
    return idx < values.length - 1
  }

  function canZoomIn(duration: OHLCDuration): boolean {
    const values = Object.values(OHLCDurationSchema.Values)
    const idx = values.indexOf(duration)
    return idx > 0
  }

  // compute the title showing the date range being displayed
  function title(): string {
    const firstDate = viewportOhlc[0]?.start
    const lastDate = viewportOhlc[viewportOhlc.length - 1]?.start
    let startDate = firstDate
    if (viewPort.earliestStart) {
      startDate = new Date(
        Math.max(firstDate.getTime(), viewPort.earliestStart.getTime())
      )
    }
    const endDate = new Date(
      viewPort.latestStart
        ? viewPort.latestStart.getTime()
        : lastDate.getTime() +
          olhcDurationsMs[viewportOhlc[viewportOhlc.length - 1].duration]
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
                disabled={!canZoomIn(duration)}
                onClick={zoomIn}
              >
                +
              </button>
              <span>{duration}</span>
              <button
                className="px-1 text-xl disabled:opacity-50"
                disabled={!canZoomOut(duration)}
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
            {
              <div className="flex w-full justify-around align-middle">
                {viewportOhlc.length > 0 && params && title()}
              </div>
            }
          </div>
          {viewportOhlc.length > 0 && params ? (
            <svg width={params.totalWidth} height={params.totalHeight}>
              {drawGridY(params)}
              {drawGridX(params, viewportOhlc)}
              {viewportOhlc.map((l, i) => (
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
