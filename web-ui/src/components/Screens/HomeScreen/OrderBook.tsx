import { Widget } from 'components/common/Widget'

export function OrderBook() {
  const orderBook = {
    last: { price: '17.50', direction: 1 },
    buy: [
      { price: '17.75', size: 2.3 },
      { price: '18.00', size: 2.8 },
      { price: '18.25', size: 5 },
      { price: '18.50', size: 10.1 },
      { price: '18.75', size: 9.5 },
      { price: '19.00', size: 12.4 },
      { price: '19.50', size: 14.2 },
      { price: '20.00', size: 15.3 },
      { price: '20.50', size: 19 }
    ],
    sell: [
      { price: '17.25', size: 2.1 },
      { price: '17.00', size: 5 },
      { price: '16.75', size: 5.4 },
      { price: '16.50', size: 7.5 },
      { price: '16.25', size: 10.1 },
      { price: '16.00', size: 7.5 },
      { price: '15.50', size: 12.4 },
      { price: '15.00', size: 11.3 },
      { price: '14.50', size: 14 },
      { price: '14.00', size: 19.5 }
    ]
  }

  const maxSize = Math.max(
    ...orderBook.sell.map((l) => l.size),
    ...orderBook.buy.map((l) => l.size)
  )

  const bookWidth = 400
  const gridLines = 7
  const graphStartX = 60
  const graphEndX = bookWidth - 20
  const graphWidth = graphEndX - graphStartX
  const graphStartY = 20
  const barHeight = 18
  const lastPriceHeight = 50
  const bookHeight =
    graphStartY +
    lastPriceHeight +
    barHeight * (orderBook.buy.length + orderBook.sell.length)
  const sellStartY =
    graphStartY + lastPriceHeight + barHeight * orderBook.buy.length
  function calculateSpacing() {
    // we want to pick "nice" tick numbers which are round-ish numbers but which mostly fill the available space
    let adjustment = 1
    let spacing = Math.floor((adjustment * maxSize) / gridLines) / adjustment
    // calculate the error from using this tick size
    let spacingError = maxSize - gridLines * spacing
    while (spacingError >= maxSize / gridLines) {
      adjustment = adjustment * 2
      spacing = Math.floor((adjustment * maxSize) / gridLines) / adjustment
      spacingError = maxSize - gridLines * spacing
    }
    return spacing
  }

  const gridSpacing = calculateSpacing()
  const ticks = []
  for (let i = 0; i <= gridLines; i++) {
    ticks.push(i * gridSpacing)
  }
  return (
    <Widget
      title={'Order Book'}
      contents={
        <svg width={bookWidth} height={bookHeight}>
          {orderBook.buy.toReversed().map((l, i) => (
            <>
              <text
                x={0}
                y={graphStartY + 4 + (i + 1) * barHeight}
                fill="white"
                textAnchor="left"
              >
                {l.price}
              </text>
              <rect
                x={graphStartX}
                width={graphWidth * (l.size / maxSize)}
                y={graphStartY + 8 + i * barHeight}
                height={barHeight}
                fill="#10A327"
              />
            </>
          ))}
          <text
            x={0}
            y={sellStartY - 12}
            fill="white"
            textAnchor="left"
            fontSize="24px"
          >
            {orderBook.last.price}
            <tspan fill={orderBook.last.direction == 1 ? '#10A327' : '#7F1D1D'}>
              {orderBook.last.direction == 1 ? '↑' : '↓'}
            </tspan>
          </text>
          {orderBook.sell.map((l, i) => (
            <>
              <text
                x={0}
                y={sellStartY + (i + 1) * barHeight - 4}
                fill="white"
                textAnchor="left"
              >
                {l.price}
              </text>
              <rect
                x={graphStartX}
                width={graphWidth * (l.size / maxSize)}
                y={sellStartY + i * barHeight}
                height={barHeight}
                fill="#7F1D1D"
              />
            </>
          ))}
          {ticks.slice(1).map((tick, i) => (
            <>
              <text
                x={graphStartX + (i + 1) * gridSpacing * (graphWidth / maxSize)}
                y={graphStartY}
                fill="white"
                textAnchor="middle"
              >
                {tick}
              </text>
              <line
                x1={
                  graphStartX + (i + 1) * gridSpacing * (graphWidth / maxSize)
                }
                y1={graphStartY + 8}
                x2={
                  graphStartX + (i + 1) * gridSpacing * (graphWidth / maxSize)
                }
                y2={bookHeight}
                stroke="white"
                strokeDasharray={4}
                strokeOpacity={0.7}
              />
            </>
          ))}
        </svg>
      }
    />
  )
}
