import { apiClient, CancelOrderRequest, Order, Trade } from 'apiClient'
import React, { useCallback, useMemo, useState } from 'react'
import { Widget } from 'components/common/Widget'
import { Address, formatUnits } from 'viem'
import { format } from 'date-fns'
import { produce } from 'immer'
import { useMutation } from '@tanstack/react-query'
import { isErrorFromAlias } from '@zodios/core'
import { classNames } from 'utils'
import Markets from 'markets'
import { useWebsocketSubscription } from 'contexts/websocket'
import { ordersTopic, Publishable, tradesTopic } from 'websocketMessages'
import { getColumnsForWidth, useWindowDimensions } from 'utils/layout'
import { generateOrderNonce, getDomain } from 'utils/eip712'
import { useConfig, useSignTypedData } from 'wagmi'
import { ChangeOrderModal } from 'components/Screens/HomeScreen/ChangeOrderModal'
import { MarketTitle } from 'components/Screens/HomeScreen/MarketSelector'
import { Status } from 'components/common/Status'
import Edit from 'assets/Edit.svg'
import Trash from 'assets/Trash.svg'

export default function OrdersAndTradesWidget({
  markets,
  exchangeContractAddress,
  walletAddress
}: {
  markets: Markets
  exchangeContractAddress: Address
  walletAddress: Address
}) {
  const [orders, setOrders] = useState<Order[]>(() => [])
  const [changedOrder, setChangedOrder] = useState<Order | null>(null)
  const [showChangeModal, setShowChangeModal] = useState<boolean>(false)
  const [trades, setTrades] = useState<Trade[]>(() => [])
  const windowDimensions = useWindowDimensions()
  const config = useConfig()
  const { signTypedDataAsync } = useSignTypedData()
  const [selectedTab, setSelectedTab] = useState<'orders' | 'trade-history'>(
    'orders'
  )

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

  async function cancelOrder(order: Order) {
    if (confirm('Are you sure you want to cancel your order?')) {
      const nonce = generateOrderNonce()
      const signature = await signTypedDataAsync({
        types: {
          EIP712Domain: [
            { name: 'name', type: 'string' },
            { name: 'version', type: 'string' },
            { name: 'chainId', type: 'uint256' },
            { name: 'verifyingContract', type: 'address' }
          ],
          CancelOrder: [
            { name: 'sender', type: 'address' },
            { name: 'marketId', type: 'string' },
            { name: 'amount', type: 'int256' },
            { name: 'nonce', type: 'int256' }
          ]
        },
        domain: getDomain(exchangeContractAddress, config.state.chainId),
        primaryType: 'CancelOrder',
        message: {
          sender: walletAddress,
          marketId: order.marketId,
          amount: order.side == 'Buy' ? order.amount : -order.amount,
          nonce: BigInt('0x' + nonce)
        }
      })
      cancelOrderMutation.mutate({
        orderId: order.id,
        amount: order.amount,
        marketId: order.marketId,
        side: order.side,
        nonce: nonce,
        signature: signature
      })
    }
  }

  const cancelOrderMutation = useMutation({
    mutationFn: (payload: CancelOrderRequest) =>
      apiClient.cancelOrder(payload, { params: { id: payload.orderId } }),
    onError: (error) => {
      alert(
        isErrorFromAlias(apiClient.api, 'cancelOrder', error)
          ? error.response.data.errors[0].displayMessage
          : 'Something went wrong'
      )
    },
    onSettled: () => {
      cancelOrderMutation.reset()
    }
  })

  function openEditModal(order: Order) {
    setChangedOrder(order)
    setShowChangeModal(true)
  }

  function ordersContent() {
    return (
      <>
        <div className="max-h-96 min-h-24 overflow-scroll">
          <table className="relative w-full text-left text-sm">
            <thead className="sticky top-0 font-normal text-darkBluishGray2">
              <tr key="header">
                <td className="pl-4">Date</td>
                <td className="pl-4">Side</td>
                <td className="pl-4">Amount</td>
                <td className="pl-4">Price</td>
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
                      <span className="mr-2 text-lightBluishGray5">
                        {format(order.timing.createdAt, 'MM/dd')}
                      </span>
                      <span className="text-white">
                        {format(order.timing.createdAt, 'HH:mm:ss a')}
                      </span>
                    </td>
                    <td className="pl-4">{order.side}</td>
                    <td className="pl-4">
                      {formatUnits(order.amount, market.baseSymbol.decimals)}
                    </td>
                    <td className="pl-4">
                      {order.type == 'limit'
                        ? order.price.toFixed(market.quoteDecimalPlaces)
                        : 'MKT'}
                    </td>
                    <td className="pl-4">
                      <MarketTitle market={market} />
                    </td>
                    <td className="pl-4 text-center">
                      <Status status={order.status} />
                    </td>
                    <td className="rounded-r py-1 pl-4">
                      {!order.isFinal() && (
                        <div className="flex items-center gap-2">
                          <button
                            className="rounded bg-darkBluishGray7 p-2 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray"
                            onClick={() => openEditModal(order)}
                            disabled={!cancelOrderMutation.isIdle}
                          >
                            <img src={Edit} alt={'Change'} />
                          </button>
                          <button
                            className="rounded bg-darkBluishGray7 p-2 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray"
                            onClick={() => cancelOrder(order)}
                            disabled={!cancelOrderMutation.isIdle}
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
            exchangeContractAddress={exchangeContractAddress}
            walletAddress={walletAddress}
            close={() => setShowChangeModal(false)}
            onClosed={() => setChangedOrder(null)}
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
            <thead className="sticky top-0 font-normal text-darkBluishGray2">
              <tr key="header">
                <td className="pl-4">Date</td>
                <td className="pl-4">Side</td>
                <td className="pl-4">Amount</td>
                <td className="pl-4">Price</td>
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
                      <span className="mr-2 text-lightBluishGray5">
                        {format(trade.timestamp, 'MM/dd')}
                      </span>
                      <span className="text-white">
                        {format(trade.timestamp, 'HH:mm:ss a')}
                      </span>
                    </td>
                    <td className="pl-4">{trade.side}</td>
                    <td className="pl-4">
                      {formatUnits(trade.amount, market.baseSymbol.decimals)}
                    </td>
                    <td className="pl-4">
                      {trade.price.toFixed(market.quoteDecimalPlaces)}
                    </td>
                    <td className="pl-4">
                      {formatUnits(
                        trade.feeAmount,
                        market.quoteSymbol.decimals
                      )}
                    </td>
                    <td className="pl-4">
                      <MarketTitle market={market} />
                    </td>
                    <td className="rounded-r pl-4 text-center">
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
      span={Math.min(2, getColumnsForWidth(windowDimensions.width))}
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
            {(function () {
              switch (selectedTab) {
                case 'orders':
                  return ordersContent()
                case 'trade-history':
                  return tradeHistoryContent()
              }
            })()}
          </div>
        </>
      }
    />
  )
}
