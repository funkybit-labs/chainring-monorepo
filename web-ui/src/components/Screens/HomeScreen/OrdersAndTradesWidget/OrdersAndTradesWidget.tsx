import { Order, OrderSide } from 'apiClient'
import React, { useCallback, useMemo, useState } from 'react'
import { Widget } from 'components/common/Widget'
import { formatUnits } from 'viem'
import { format } from 'date-fns'
import { produce } from 'immer'
import { calculateNotional, classNames } from 'utils'
import Markets, { Market } from 'markets'
import { useWebsocketSubscription } from 'contexts/websocket'
import { myOrdersTopic, Publishable, myTradesTopic } from 'websocketMessages'
import { CancelOrderModal } from 'components/Screens/HomeScreen/CancelOrderModal'
import { Status } from 'components/common/Status'
import Trash from 'assets/Trash.svg'
import { SymbolAndChain } from 'components/common/SymbolAndChain'
import Decimal from 'decimal.js'
import { scaledDecimalToBigint } from 'utils/pricesUtils'
import { ExpandableValue } from 'components/common/ExpandableValue'
import { useSwitchToEthChain } from 'utils/switchToEthChain'
import { ConnectWallet } from 'components/Screens/HomeScreen/swap/ConnectWallet'
import {
  rollupTrades,
  OrderTradesGroup
} from 'components/Screens/HomeScreen/OrdersAndTradesWidget/tradeRollup'
import arrowRightIcon from 'assets/arrow-right.svg'
import arrowDownIcon from 'assets/arrow-down.svg'

type Tab = 'Orders' | 'Trade History'

export default function OrdersAndTradesWidget({
  markets,
  exchangeContractAddress,
  walletAddress
}: {
  markets: Markets
  exchangeContractAddress?: string
  walletAddress?: string
}) {
  const [orders, setOrders] = useState<Order[]>(() => [])
  const [cancellingOrder, setCancellingOrder] = useState<Order | null>(null)
  const [showCancelModal, setShowCancelModal] = useState<boolean>(false)
  const [orderTradeGroups, setOrderTradeGroups] = useState<OrderTradesGroup[]>(
    () => []
  )
  const [selectedTab, setSelectedTab] = useState<Tab>('Orders')
  const switchToEthChain = useSwitchToEthChain()

  useWebsocketSubscription({
    topics: useMemo(() => [myOrdersTopic, myTradesTopic], []),
    handler: useCallback(
      (message: Publishable) => {
        if (message.type === 'MyOrders') {
          setOrders(message.orders)
        } else if (message.type === 'MyOrderCreated') {
          setOrders(
            produce((draft) => {
              draft.unshift(message.order)
            })
          )
        } else if (message.type === 'MyOrderUpdated') {
          setOrders(
            produce((draft) => {
              const updatedOrder = message.order
              const index = draft.findIndex(
                (order) => order.id === updatedOrder.id
              )
              if (index !== -1) draft[index] = updatedOrder
            })
          )
        } else if (message.type === 'MyTrades') {
          setOrderTradeGroups((prevState) => {
            const expanded = new Set<string>()
            prevState.forEach((orderTradeGroup) => {
              if (orderTradeGroup.expanded) {
                expanded.add(orderTradeGroup.id)
              }
            })

            const newState = rollupTrades(message.trades, markets)
            // make sure rows that were expanded stay expanded
            newState.forEach((orderTradeGroup) => {
              orderTradeGroup.expanded = expanded.has(orderTradeGroup.id)
            })

            return newState
          })
        } else if (message.type === 'MyTradesCreated') {
          setOrderTradeGroups(
            produce((draft) => {
              rollupTrades(message.trades, markets).forEach(
                (orderTradesGroup) => draft.unshift(orderTradesGroup)
              )
            })
          )
        } else if (message.type === 'MyTradesUpdated') {
          setOrderTradeGroups(
            produce((draft) => {
              rollupTrades(message.trades, markets).forEach(
                (updatedOrderTradesGroup) => {
                  const index = draft.findIndex(
                    (orderTradeGroup) =>
                      orderTradeGroup.id === updatedOrderTradesGroup.id
                  )
                  if (index !== -1) {
                    updatedOrderTradesGroup.expanded = draft[index].expanded
                    draft[index] = updatedOrderTradesGroup
                  }
                }
              )
            })
          )
        }
      },
      [markets]
    )
  })

  function openCancelModal(order: Order) {
    setCancellingOrder(order)
    setShowCancelModal(true)
  }

  function ordersContent() {
    return (
      <>
        <div className="h-96 overflow-scroll">
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
                const secondMarket =
                  order.type === 'backToBackMarket'
                    ? markets.getById(order.secondMarketId)
                    : null

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
                            ? secondMarket?.quoteSymbol ?? market.quoteSymbol
                            : market.baseSymbol
                        }
                      />
                    </td>
                    <td className="pl-4">
                      <SymbolAndChain
                        symbol={
                          order.side === 'Buy'
                            ? market.baseSymbol
                            : secondMarket?.quoteSymbol ?? market.quoteSymbol
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
          {market.baseSymbol.displayName()}
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
            {market.baseSymbol.displayName()}
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

  function toggleExpandOrderTradesGroup(groupId: string) {
    setOrderTradeGroups(
      produce((draft) => {
        const aggregateIndex = draft.findIndex((ta) => ta.id === groupId)
        if (aggregateIndex !== -1) {
          const tradeAggregate = draft[aggregateIndex]
          tradeAggregate.expanded = !tradeAggregate.expanded
        }
      })
    )
  }

  function tradeHistoryContent() {
    return (
      <>
        <div className="h-96 overflow-scroll">
          <table className="relative w-full text-left text-sm">
            <thead className="sticky top-0 z-10 bg-darkBluishGray9 font-normal text-darkBluishGray2">
              <tr key="header">
                <td className="pl-4"></td>
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
              {orderTradeGroups.map((orderTradesGroup) => {
                return (
                  <React.Fragment key={orderTradesGroup.id}>
                    <tr
                      key={orderTradesGroup.id}
                      className="duration-200 ease-in-out hover:cursor-default hover:bg-darkBluishGray6"
                      onClick={() =>
                        toggleExpandOrderTradesGroup(orderTradesGroup.id)
                      }
                    >
                      <td className="rounded-l pl-4">
                        {orderTradesGroup.trades.length > 1 &&
                          (orderTradesGroup.expanded ? (
                            <img className="size-3" src={arrowDownIcon} />
                          ) : (
                            <img className="size-3" src={arrowRightIcon} />
                          ))}
                      </td>
                      <td className="h-12 pl-4">
                        <span className="mr-2 inline-block text-lightBluishGray5">
                          {format(orderTradesGroup.timestamp, 'MM/dd')}
                        </span>
                        <br className="narrow:hidden" />
                        <span className="whitespace-nowrap text-white">
                          {format(orderTradesGroup.timestamp, 'HH:mm:ss a')}
                        </span>
                      </td>
                      <td className="pl-4">
                        <SymbolAndChain symbol={orderTradesGroup.sellSymbol} />
                      </td>
                      <td className="pl-4">
                        <SymbolAndChain symbol={orderTradesGroup.buySymbol} />
                      </td>
                      <td className="hidden w-96 pl-4 narrow:table-cell">
                        <ExpandableValue
                          value={formatUnits(
                            orderTradesGroup.aggregatedAmount,
                            orderTradesGroup.sellSymbol.decimals
                          )}
                        />{' '}
                        {orderTradesGroup.sellSymbol.displayName()}
                        {' ('}
                        {orderTradesGroup.sellSymbol.chainName}
                        {')'}
                      </td>
                      <td className="hidden pl-4 narrow:table-cell">
                        {orderTradesGroup.aggregatedPrice.toFixed(
                          orderTradesGroup.priceDecimalPlaces
                        )}
                      </td>
                      <td className="table-cell pl-4 narrow:hidden">
                        {formatUnits(
                          orderTradesGroup.aggregatedAmount,
                          orderTradesGroup.sellSymbol.decimals
                        )}
                        <br />
                        {orderTradesGroup.aggregatedPrice.toFixed(
                          orderTradesGroup.priceDecimalPlaces
                        )}
                      </td>

                      <td className="pl-4">
                        <ExpandableValue
                          value={formatUnits(
                            orderTradesGroup.aggregatedFeeAmount,
                            orderTradesGroup.feeSymbol.decimals
                          )}
                        />{' '}
                        <SymbolAndChain
                          symbol={orderTradesGroup.feeSymbol}
                          noIcon={true}
                        />
                      </td>
                      <td className="rounded-r px-4 text-center">
                        <Status status={orderTradesGroup.settlementStatus} />
                      </td>
                    </tr>
                    {orderTradesGroup.trades.length > 1 &&
                      orderTradesGroup.expanded &&
                      orderTradesGroup.trades.map((orderTrade) => {
                        return (
                          <tr
                            key={`${orderTradesGroup.id}-${orderTrade.id}`}
                            className="bg-darkBluishGray10/[0.5] text-xs duration-200 ease-in-out hover:cursor-default hover:bg-darkBluishGray6"
                          >
                            <td className="rounded-l pl-4"></td>
                            <td className="h-12 pl-7"></td>
                            <td className="pl-7">
                              <SymbolAndChain
                                symbol={orderTrade.sellSymbol}
                                className={'text-xs'}
                                iconSize={4}
                              />
                            </td>
                            <td className="pl-7">
                              <SymbolAndChain
                                symbol={orderTrade.buySymbol}
                                className={'text-xs'}
                                iconSize={4}
                              />
                            </td>
                            <td className="hidden pl-7 narrow:table-cell">
                              <ExpandableValue
                                value={formatUnits(
                                  orderTrade.amount,
                                  orderTrade.sellSymbol.decimals
                                )}
                              />{' '}
                              {orderTrade.sellSymbol.displayName()}
                              {' ('}
                              {orderTrade.sellSymbol.chainName}
                              {')'}
                            </td>
                            <td className="hidden pl-7 narrow:table-cell">
                              {orderTrade.price.toFixed(
                                orderTrade.priceDecimalPlaces
                              )}
                            </td>
                            <td className="table-cell pl-7 narrow:hidden">
                              {formatUnits(
                                orderTrade.amount,
                                orderTrade.sellSymbol.decimals
                              )}
                              <br />
                              {orderTrade.price.toFixed(
                                orderTrade.priceDecimalPlaces
                              )}
                            </td>
                            <td className="pl-7">
                              {orderTrade.feeAmount > 0 && (
                                <>
                                  <ExpandableValue
                                    value={formatUnits(
                                      orderTrade.feeAmount,
                                      orderTrade.feeSymbol.decimals
                                    )}
                                  />{' '}
                                  <SymbolAndChain
                                    symbol={orderTrade.feeSymbol}
                                    className={'text-xs'}
                                    noIcon={true}
                                  />
                                </>
                              )}
                            </td>
                            <td className="rounded-r px-4 text-center"></td>
                          </tr>
                        )
                      })}
                  </React.Fragment>
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
