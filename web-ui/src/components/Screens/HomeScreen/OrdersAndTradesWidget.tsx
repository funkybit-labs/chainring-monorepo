import { Order, OrderSide, Trade } from 'apiClient'
import React, { useCallback, useMemo, useState } from 'react'
import { Widget } from 'components/common/Widget'
import { Address, formatUnits } from 'viem'
import { format } from 'date-fns'
import { produce } from 'immer'
import { calculateNotional, classNames } from 'utils'
import Markets, { Market } from 'markets'
import { useWebsocketSubscription } from 'contexts/websocket'
import { ordersTopic, Publishable, tradesTopic } from 'websocketMessages'
import { CancelOrderModal } from 'components/Screens/HomeScreen/CancelOrderModal'
import { Status } from 'components/common/Status'
import Trash from 'assets/Trash.svg'
import { SymbolAndChain } from 'components/common/SymbolAndChain'
import Decimal from 'decimal.js'
import { scaledDecimalToBigint } from 'utils/pricesUtils'
import { ExpandableValue } from 'components/common/ExpandableValue'
import { useSwitchToEthChain } from 'utils/switchToEthChain'
import { ConnectWallet } from 'components/Screens/HomeScreen/swap/ConnectWallet'

type Tab = 'Orders' | 'Trade History'

export default function OrdersAndTradesWidget({
  markets,
  exchangeContractAddress,
  walletAddress
}: {
  markets: Markets
  exchangeContractAddress?: Address
  walletAddress?: Address
}) {
  const [orders, setOrders] = useState<Order[]>(() => [])
  const [cancellingOrder, setCancellingOrder] = useState<Order | null>(null)
  const [showCancelModal, setShowCancelModal] = useState<boolean>(false)
  const [trades, setTrades] = useState<Trade[]>(() => [])
  const [selectedTab, setSelectedTab] = useState<Tab>('Orders')
  const switchToEthChain = useSwitchToEthChain()

  useWebsocketSubscription({
    topics: useMemo(() => [ordersTopic, tradesTopic], []),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'Orders') {
        setOrders(message.orders)
      } else if (message.type === 'OrderCreated') {
        setOrders(
          produce((draft) => {
            draft.unshift(message.order)
          })
        )
      } else if (message.type === 'OrderUpdated') {
        setOrders(
          produce((draft) => {
            const updatedOrder = message.order
            const index = draft.findIndex(
              (order) => order.id === updatedOrder.id
            )
            if (index !== -1) draft[index] = updatedOrder
          })
        )
      } else if (message.type === 'Trades') {
        setTrades(message.trades)
      } else if (message.type === 'TradeCreated') {
        setTrades(
          produce((draft) => {
            draft.unshift(message.trade)
          })
        )
      } else if (message.type === 'TradeUpdated') {
        setTrades(
          produce((draft) => {
            const updatedTrade = message.trade
            const index = draft.findIndex(
              (trade) =>
                trade.id === updatedTrade.id && trade.side === updatedTrade.side
            )
            if (index !== -1) draft[index] = updatedTrade
          })
        )
      }
    }, [])
  })

  function openCancelModal(order: Order) {
    setCancellingOrder(order)
    setShowCancelModal(true)
  }

  function ordersContent() {
    return (
      <>
        <div className="max-h-96 min-h-24 overflow-scroll">
          <table className="relative w-full text-left text-sm">
            <thead className="sticky top-0 z-10 bg-darkBluishGray9 font-normal text-darkBluishGray2">
              <tr key="header">
                <td className="pl-4">Date</td>
                <td className="pl-4">Sell</td>
                <td className="pl-4">Buy</td>
                <td className="hidden pl-4 narrow:table-cell">Amount</td>
                <td className="hidden pl-4 narrow:table-cell">Price</td>
                <td className="table-cell pl-4 narrow:hidden">
                  Amount
                  <br />
                  Price
                </td>
                <td className="pl-4 text-left">Status</td>
                <td className="px-4">Cancel</td>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => {
                const market = markets.getById(order.marketId)

                return (
                  <tr
                    key={order.id}
                    className={classNames(
                      'duration-200 ease-in-out hover:cursor-default hover:bg-darkBluishGray6'
                    )}
                  >
                    <td className="h-12 rounded-l pl-4">
                      <span className="mr-2 inline-block text-lightBluishGray5">
                        {format(order.timing.createdAt, 'MM/dd')}
                      </span>
                      <span className="inline-block whitespace-nowrap text-white">
                        {format(order.timing.createdAt, 'HH:mm:ss a')}
                      </span>
                    </td>
                    <td className="pl-4">
                      <SymbolAndChain
                        symbol={
                          order.side === 'Buy'
                            ? market.quoteSymbol
                            : market.baseSymbol
                        }
                      />
                    </td>
                    <td className="pl-4">
                      <SymbolAndChain
                        symbol={
                          order.side === 'Buy'
                            ? market.baseSymbol
                            : market.quoteSymbol
                        }
                      />
                    </td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {displayAmount(
                        order.amount,
                        order.side,
                        market,
                        undefined
                      )}
                    </td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {order.type == 'limit'
                        ? displayPrice(order.price, order.side, market)
                        : 'MKT'}
                    </td>
                    <td className="table-cell pl-4 narrow:hidden">
                      {formatUnits(order.amount, market.baseSymbol.decimals)}
                      <br />
                      {order.type == 'limit'
                        ? displayPrice(order.price, order.side, market)
                        : 'MKT'}
                    </td>
                    <td className="pl-4 text-center">
                      <Status status={order.status} />
                    </td>
                    <td className="rounded-r px-4 py-1">
                      {!order.isFinal() && (
                        <div className="flex items-center gap-2">
                          <button
                            className="shrink-0 rounded bg-darkBluishGray7 p-2 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray"
                            onClick={() => openCancelModal(order)}
                          >
                            <img src={Trash} alt={'Cancel'} />
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>

        {cancellingOrder && (
          <CancelOrderModal
            isOpen={showCancelModal}
            order={cancellingOrder}
            exchangeContractAddress={exchangeContractAddress!}
            walletAddress={walletAddress!}
            close={() => setShowCancelModal(false)}
            onClosed={() => setCancellingOrder(null)}
          />
        )}
      </>
    )
  }

  function displayAmount(
    amount: bigint,
    side: OrderSide,
    market: Market,
    price?: Decimal
  ): JSX.Element {
    if (side === 'Sell') {
      return (
        <>
          <ExpandableValue
            value={formatUnits(amount, market.baseSymbol.decimals)}
          />{' '}
          {market.baseSymbol.name}
          {' ('}
          {market.baseSymbol.chainName}
          {')'}
        </>
      )
    } else {
      if (price) {
        return (
          <ExpandableValue
            value={formatUnits(
              calculateNotional(
                scaledDecimalToBigint(price, market.quoteSymbol.decimals),
                amount,
                market.baseSymbol
              ),
              market.quoteSymbol.decimals
            )}
          />
        )
      } else {
        return (
          <>
            <ExpandableValue
              value={formatUnits(amount, market.baseSymbol.decimals)}
            />{' '}
            {market.baseSymbol.name}
            {' ('}
            {market.baseSymbol.chainName}
            {')'}
          </>
        )
      }
    }
  }

  function displayPrice(
    price: Decimal,
    side: OrderSide,
    market: Market
  ): string {
    if (side === 'Sell') {
      return price.toFixed(market.quoteDecimalPlaces)
    } else {
      const invertedPrice = new Decimal(1).div(price)
      return invertedPrice.toFixed(6)
    }
  }

  function tradeHistoryContent() {
    return (
      <>
        <div className="max-h-96 min-h-24 overflow-scroll">
          <table className="relative w-full text-left text-sm">
            <thead className="sticky top-0 z-10 bg-darkBluishGray9 font-normal text-darkBluishGray2">
              <tr key="header">
                <td className="pl-4">Date</td>
                <td className="pl-4">Sell</td>
                <td className="pl-4">Buy</td>
                <td className="hidden pl-4 narrow:table-cell">Amount</td>
                <td className="hidden pl-4 narrow:table-cell">Price</td>
                <td className="table-cell pl-4 narrow:hidden">
                  Amount
                  <br />
                  Price
                </td>
                <td className="pl-4">Fee</td>
                <td className="pl-4 text-center">Status</td>
              </tr>
            </thead>
            <tbody>
              {trades.map((trade) => {
                const market = markets.getById(trade.marketId)

                return (
                  <tr
                    key={`${trade.id}-${trade.side}`}
                    className="duration-200 ease-in-out hover:cursor-default hover:bg-darkBluishGray6"
                  >
                    <td className="h-12 rounded-l pl-4">
                      <span className="mr-2 inline-block text-lightBluishGray5">
                        {format(trade.timestamp, 'MM/dd')}
                      </span>
                      <br className="narrow:hidden" />
                      <span className="whitespace-nowrap text-white">
                        {format(trade.timestamp, 'HH:mm:ss a')}
                      </span>
                    </td>
                    <td className="pl-4">
                      <SymbolAndChain
                        symbol={
                          trade.side === 'Buy'
                            ? market.quoteSymbol
                            : market.baseSymbol
                        }
                      />
                    </td>
                    <td className="pl-4">
                      <SymbolAndChain
                        symbol={
                          trade.side === 'Buy'
                            ? market.baseSymbol
                            : market.quoteSymbol
                        }
                      />
                    </td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {displayAmount(
                        trade.amount,
                        trade.side,
                        market,
                        trade.price
                      )}
                    </td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {displayPrice(trade.price, trade.side, market)}
                    </td>
                    <td className="table-cell pl-4 narrow:hidden">
                      {formatUnits(trade.amount, market.baseSymbol.decimals)}
                      <br />
                      {trade.price.toFixed(market.quoteDecimalPlaces)}
                    </td>

                    <td className="pl-4">
                      <ExpandableValue
                        value={formatUnits(
                          trade.feeAmount,
                          market.quoteSymbol.decimals
                        )}
                      />{' '}
                      <SymbolAndChain
                        symbol={market.quoteSymbol}
                        noIcon={true}
                      />
                    </td>
                    <td className="rounded-r px-4 text-center">
                      <Status status={trade.settlementStatus} />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </>
    )
  }

  return (
    <Widget
      id="orders-and-trades"
      contents={
        <>
          <div className="flex w-full space-x-2 text-center font-medium">
            {(['Orders', 'Trade History'] as Tab[]).map((t) => (
              <div
                key={t}
                className={classNames(
                  'cursor-pointer border-b-2 pb-2 w-full h-10 flex-col content-end transition-colors',
                  selectedTab === t
                    ? 'text-statusOrange'
                    : 'text-darkBluishGray3 hover:text-white'
                )}
                onClick={() => setSelectedTab(t)}
              >
                {t}
              </div>
            ))}
          </div>
          <div className="mt-4">
            {walletAddress !== undefined &&
            exchangeContractAddress !== undefined ? (
              (function () {
                switch (selectedTab) {
                  case 'Orders':
                    return ordersContent()
                  case 'Trade History':
                    return tradeHistoryContent()
                }
              })()
            ) : (
              <div className="flex w-full flex-col place-items-center">
                <div className="mb-4 text-darkBluishGray2">
                  If you want to see your{' '}
                  {selectedTab === 'Orders' ? 'orders' : 'trade history'},
                  connect your wallet.
                </div>
                <ConnectWallet
                  onSwitchToChain={(chainId) => switchToEthChain(chainId)}
                />
              </div>
            )}
          </div>
        </>
      }
    />
  )
}
