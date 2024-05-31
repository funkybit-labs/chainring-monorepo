import { FeeRates, Order, Trade } from 'apiClient'
import React, { useCallback, useMemo, useState } from 'react'
import { Widget } from 'components/common/Widget'
import { Address, formatUnits } from 'viem'
import { format } from 'date-fns'
import { produce } from 'immer'
import { classNames } from 'utils'
import Markets from 'markets'
import { useWebsocketSubscription } from 'contexts/websocket'
import { ordersTopic, Publishable, tradesTopic } from 'websocketMessages'
import { CancelOrderModal } from 'components/Screens/HomeScreen/CancelOrderModal'
import { ChangeOrderModal } from 'components/Screens/HomeScreen/ChangeOrderModal'
import { MarketTitle } from 'components/Screens/HomeScreen/MarketSelector'
import { Status } from 'components/common/Status'
import Edit from 'assets/Edit.svg'
import Trash from 'assets/Trash.svg'
import { Button } from 'components/common/Button'
import { useWeb3Modal } from '@web3modal/wagmi/react'

export default function OrdersAndTradesWidget({
  markets,
  exchangeContractAddress,
  walletAddress,
  feeRates
}: {
  markets: Markets
  exchangeContractAddress?: Address
  walletAddress?: Address
  feeRates: FeeRates
}) {
  const [orders, setOrders] = useState<Order[]>(() => [])
  const [changedOrder, setChangedOrder] = useState<Order | null>(null)
  const [showChangeModal, setShowChangeModal] = useState<boolean>(false)
  const [cancellingOrder, setCancellingOrder] = useState<Order | null>(null)
  const [showCancelModal, setShowCancelModal] = useState<boolean>(false)
  const [trades, setTrades] = useState<Trade[]>(() => [])
  const [selectedTab, setSelectedTab] = useState<'orders' | 'trade-history'>(
    'orders'
  )
  const { open: openWalletConnectModal } = useWeb3Modal()

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

  function openEditModal(order: Order) {
    setChangedOrder(order)
    setShowChangeModal(true)
  }

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
                <td className="pl-4">Side</td>
                <td className="hidden pl-4 narrow:table-cell">Amount</td>
                <td className="hidden pl-4 narrow:table-cell">Price</td>
                <td className="table-cell pl-4 narrow:hidden">
                  Amount
                  <br />
                  Price
                </td>
                <td className="pl-4">Market</td>
                <td className="pl-4 text-center">Status</td>
                <td className="px-4">Edit</td>
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
                    <td className="pl-4">{order.side}</td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {formatUnits(order.amount, market.baseSymbol.decimals)}
                    </td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {order.type == 'limit'
                        ? order.price.toFixed(market.quoteDecimalPlaces)
                        : 'MKT'}
                    </td>
                    <td className="table-cell pl-4 narrow:hidden">
                      {formatUnits(order.amount, market.baseSymbol.decimals)}
                      <br />
                      {order.type == 'limit'
                        ? order.price.toFixed(market.quoteDecimalPlaces)
                        : 'MKT'}
                    </td>
                    <td className="pl-4">
                      <MarketTitle market={market} alwaysShowLabel={false} />
                    </td>
                    <td className="pl-4 text-center">
                      <Status status={order.status} />
                    </td>
                    <td className="rounded-r px-4 py-1">
                      {!order.isFinal() && (
                        <div className="flex items-center gap-2">
                          <button
                            className="shrink-0 rounded bg-darkBluishGray7 p-2 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray"
                            onClick={() => openEditModal(order)}
                          >
                            <img src={Edit} alt={'Change'} />
                          </button>
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

        {changedOrder && (
          <ChangeOrderModal
            isOpen={showChangeModal}
            order={changedOrder}
            markets={markets}
            exchangeContractAddress={exchangeContractAddress!}
            walletAddress={walletAddress!}
            feeRates={feeRates}
            close={() => setShowChangeModal(false)}
            onClosed={() => setChangedOrder(null)}
          />
        )}

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

  function tradeHistoryContent() {
    return (
      <>
        <div className="max-h-96 min-h-24 overflow-scroll">
          <table className="relative w-full text-left text-sm">
            <thead className="sticky top-0 z-10 bg-darkBluishGray9 font-normal text-darkBluishGray2">
              <tr key="header">
                <td className="pl-4">Date</td>
                <td className="pl-4">Side</td>
                <td className="hidden pl-4 narrow:table-cell">Amount</td>
                <td className="hidden pl-4 narrow:table-cell">Price</td>
                <td className="table-cell pl-4 narrow:hidden">
                  Amount
                  <br />
                  Price
                </td>
                <td className="pl-4">Fee</td>
                <td className="pl-4">Market</td>
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
                    <td className="pl-4">{trade.side}</td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {formatUnits(trade.amount, market.baseSymbol.decimals)}
                    </td>
                    <td className="hidden pl-4 narrow:table-cell">
                      {trade.price.toFixed(market.quoteDecimalPlaces)}
                    </td>
                    <td className="table-cell pl-4 narrow:hidden">
                      {formatUnits(trade.amount, market.baseSymbol.decimals)}
                      <br />
                      {trade.price.toFixed(market.quoteDecimalPlaces)}
                    </td>
                    <td className="pl-4">
                      {formatUnits(
                        trade.feeAmount,
                        market.quoteSymbol.decimals
                      )}
                    </td>
                    <td className="pl-4">
                      <MarketTitle market={market} alwaysShowLabel={false} />
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
          <div className="flex w-full text-center font-medium">
            <div
              className={classNames(
                'cursor-pointer border-b-2 mr-4 w-full h-10 flex-col content-end pb-1',
                selectedTab == 'orders'
                  ? 'border-b-primary4'
                  : 'border-b-darkBluishGray3'
              )}
              onClick={() => setSelectedTab('orders')}
            >
              <div
                className={
                  selectedTab == 'orders'
                    ? 'text-primary4'
                    : 'text-darkBluishGray3'
                }
              >
                Orders
              </div>
            </div>
            <div
              className={classNames(
                'cursor-pointer border-b-2 ml-4 w-full h-10 flex-col content-end pb-1',
                selectedTab == 'trade-history'
                  ? 'border-b-primary4'
                  : 'border-b-darkBluishGray3'
              )}
              onClick={() => setSelectedTab('trade-history')}
            >
              <div
                className={
                  selectedTab == 'trade-history'
                    ? 'text-primary4'
                    : 'text-darkBluishGray3'
                }
              >
                Trade History
              </div>
            </div>
          </div>
          <div className="mt-4">
            {walletAddress !== undefined &&
            exchangeContractAddress !== undefined ? (
              (function () {
                switch (selectedTab) {
                  case 'orders':
                    return ordersContent()
                  case 'trade-history':
                    return tradeHistoryContent()
                }
              })()
            ) : (
              <div className="flex w-full flex-col place-items-center">
                <div className="mb-4 text-darkBluishGray2">
                  If you want to see your{' '}
                  {selectedTab == 'orders' ? 'orders' : 'trade history'},
                  connect your wallet.
                </div>
                <Button
                  primary={true}
                  caption={() => <>Connect Wallet</>}
                  style={'normal'}
                  disabled={false}
                  onClick={() => openWalletConnectModal({ view: 'Connect' })}
                />
              </div>
            )}
          </div>
        </>
      }
    />
  )
}
