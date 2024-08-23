import { Modal } from 'components/common/Modal'
import SubmitButton from 'components/common/SubmitButton'
import CancelButton from 'components/common/CancelButton'
import { apiClient, CancelOrderRequest, Order } from 'apiClient'
import { generateOrderNonce, getDomain } from 'utils/eip712'
import { useMutation } from '@tanstack/react-query'
import { isErrorFromAlias } from '@zodios/core'
import { BaseError as WagmiError, useConfig, useSignTypedData } from 'wagmi'
import { Address } from 'viem'

export function CancelOrderModal({
  order,
  exchangeContractAddress,
  walletAddress,
  isOpen,
  close,
  onClosed
}: {
  order: Order
  exchangeContractAddress: string
  walletAddress: string
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const config = useConfig()
  const { signTypedDataAsync } = useSignTypedData()

  const cancelOrderMutation = useMutation({
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
            CancelOrder: [
              { name: 'sender', type: 'address' },
              { name: 'marketId', type: 'string' },
              { name: 'amount', type: 'int256' },
              { name: 'nonce', type: 'int256' }
            ]
          },
          domain: getDomain(exchangeContractAddress!, config.state.chainId),
          primaryType: 'CancelOrder',
          message: {
            sender: walletAddress! as Address,
            marketId: order.marketId,
            amount: order.side == 'Buy' ? order.amount : -order.amount,
            nonce: BigInt('0x' + nonce)
          }
        })

        const payload: CancelOrderRequest = {
          orderId: order.id,
          amount: order.amount,
          marketId: order.marketId,
          side: order.side,
          nonce: nonce,
          signature: signature,
          verifyingChainId: config.state.chainId
        }

        return await apiClient.cancelOrder(payload, {
          params: { id: payload.orderId }
        })
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'cancelOrder', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: () => {
      close()
    }
  })

  async function onSubmit() {
    cancelOrderMutation.mutate()
  }

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={onClosed}
      title={'Cancel order'}
    >
      <div className="overflow-y-auto text-sm text-white">
        <div className="mb-4 text-center text-darkBluishGray1">
          Are you sure you want to cancel your order?
        </div>

        <div className="flex flex-row gap-4">
          <div className="w-full flex-col">
            <CancelButton
              disabled={false}
              onClick={close}
              caption={() => 'Cancel'}
            />
          </div>

          <div className="w-full flex-col">
            <SubmitButton
              disabled={
                cancelOrderMutation.isPending || cancelOrderMutation.isSuccess
              }
              onClick={onSubmit}
              error={cancelOrderMutation.error?.message}
              caption={() => {
                if (cancelOrderMutation.isPending) {
                  return 'Submitting...'
                } else {
                  return 'Ok'
                }
              }}
              status={'idle'}
            />
          </div>
        </div>
      </div>
    </Modal>
  )
}
