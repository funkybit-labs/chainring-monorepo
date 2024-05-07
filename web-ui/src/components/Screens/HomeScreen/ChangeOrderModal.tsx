import { apiClient, Order } from 'apiClient'
import Markets from 'markets'
import { Address, formatUnits, parseUnits } from 'viem'
import { BaseError as WagmiError, useConfig, useSignTypedData } from 'wagmi'
import useAmountInputState from 'hooks/useAmountInputState'
import { useCallback, useMemo, useState } from 'react'
import { useWebsocketSubscription } from 'contexts/websocket'
import { limitsTopic, Publishable } from 'websocketMessages'
import { useMutation } from '@tanstack/react-query'
import { addressZero, generateOrderNonce, getDomain } from 'utils/eip712'
import Decimal from 'decimal.js'
import { Modal } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import { AmountWithSymbol } from 'components/common/AmountWithSymbol'
import SubmitButton from 'components/common/SubmitButton'
import { isErrorFromAlias } from '@zodios/core'

export function ChangeOrderModal({
  order,
  markets,
  exchangeContractAddress,
  walletAddress,
  isOpen,
  close,
  onClosed
}: {
  order: Order
  markets: Markets
  exchangeContractAddress: Address
  walletAddress: Address
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const config = useConfig()
  const market = markets.getById(order.marketId)
  const baseSymbol = market.baseSymbol
  const quoteSymbol = market.quoteSymbol
  const { signTypedDataAsync } = useSignTypedData()

  const {
    inputValue: amountInputValue,
    setInputValue: setAmountInputValue,
    valueInFundamentalUnits: amount
  } = useAmountInputState({
    initialInputValue: formatUnits(order.amount, baseSymbol.decimals),
    decimals: baseSymbol.decimals
  })

  const {
    inputValue: priceInputValue,
    setInputValue: setPriceInputValue,
    valueInFundamentalUnits: price
  } = useAmountInputState({
    initialInputValue: order.type == 'limit' ? String(order.price) : '',
    decimals: baseSymbol.decimals
  })

  const [baseLimit, setBaseLimit] = useState<bigint | undefined>(undefined)
  const [quoteLimit, setQuoteLimit] = useState<bigint | undefined>(undefined)

  useWebsocketSubscription({
    topics: useMemo(() => [limitsTopic(market.id)], [market.id]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'Limits') {
        setBaseLimit(message.base)
        setQuoteLimit(message.quote)
      }
    }, [])
  })

  const exceedsLimit = useMemo(() => {
    if (
      quoteLimit === undefined ||
      baseLimit === undefined ||
      order.type == 'market'
    ) {
      return false
    }

    const originalAmount = order.amount
    const originalPrice = parseUnits(String(order.price), quoteSymbol.decimals)

    if (order.side == 'Buy') {
      return price * amount - originalPrice * originalAmount > quoteLimit
    } else {
      return amount - originalAmount > baseLimit
    }
  }, [price, amount, order, baseLimit, quoteLimit, quoteSymbol.decimals])

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        const nonce = generateOrderNonce()
        const signature = await signTypedDataAsync({
          types: {
            EIP712Domain: [
              { name: 'name', type: 'string' },
              { name: 'version', type: 'string' },
              { name: 'chainId', type: 'uint256' },
              { name: 'verifyingContract', type: 'address' }
            ],
            Order: [
              { name: 'sender', type: 'address' },
              { name: 'baseToken', type: 'address' },
              { name: 'quoteToken', type: 'address' },
              { name: 'amount', type: 'int256' },
              { name: 'price', type: 'uint256' },
              { name: 'nonce', type: 'int256' }
            ]
          },
          domain: getDomain(exchangeContractAddress, config.state.chainId),
          primaryType: 'Order',
          message: {
            sender: walletAddress,
            baseToken: baseSymbol.contractAddress ?? addressZero,
            quoteToken: quoteSymbol.contractAddress ?? addressZero,
            amount: order.side == 'Buy' ? amount : -amount,
            price: BigInt(price),
            nonce: BigInt('0x' + nonce)
          }
        })

        return await apiClient.updateOrder(
          {
            orderId: order.id,
            type: 'limit',
            amount: amount,
            price: new Decimal(priceInputValue),
            marketId: order.marketId,
            side: order.side,
            nonce: nonce,
            signature: signature
          },
          { params: { id: order.id } }
        )
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'updateOrder', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: () => {
      close()
    }
  })

  const canSubmit = useMemo(() => {
    if (mutation.isPending) return false
    if (amount <= 0n) return false
    if (price <= 0n && order.type === 'market') return false
    return !exceedsLimit
  }, [amount, price, order.type, mutation.isPending, exceedsLimit])

  async function onSubmit() {}

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={onClosed}
      title={'Change order'}
    >
      <div className="overflow-y-auto text-sm">
        <div className="flex flex-col gap-2">
          <div>Market: {order.marketId}</div>
          <div>Side: {order.side}</div>
          <div>
            <label className="block">Amount</label>
            <AmountInput
              value={amountInputValue}
              symbol={baseSymbol.name}
              disabled={mutation.isPending}
              onChange={(e) => setAmountInputValue(e.target.value)}
            />
          </div>

          {order.type === 'limit' && (
            <div>
              <label className="block">Price</label>
              <AmountInput
                value={priceInputValue}
                symbol={quoteSymbol.name}
                disabled={mutation.isPending}
                onChange={(e) => setPriceInputValue(e.target.value)}
              />
            </div>
          )}
        </div>

        {exceedsLimit &&
          quoteLimit !== undefined &&
          baseLimit !== undefined && (
            <div className="mt-2 text-center text-sm text-brightRed">
              Will exceed the limit of{' '}
              {order.side == 'Buy' ? (
                <AmountWithSymbol
                  amount={quoteLimit}
                  symbol={quoteSymbol}
                  approximate={false}
                />
              ) : (
                <AmountWithSymbol
                  amount={baseLimit}
                  symbol={baseSymbol}
                  approximate={false}
                />
              )}
            </div>
          )}

        <SubmitButton
          disabled={!canSubmit}
          onClick={onSubmit}
          error={mutation.error?.message}
          caption={() => {
            if (mutation.isPending) {
              return 'Submitting...'
            } else {
              return 'Submit'
            }
          }}
        />
      </div>
    </Modal>
  )
}
