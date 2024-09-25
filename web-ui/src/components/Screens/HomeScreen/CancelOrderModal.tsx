import { Modal } from 'components/common/Modal'
import SubmitButton from 'components/common/SubmitButton'
import CancelButton from 'components/common/CancelButton'
import { apiClient, CancelOrderRequest, Order } from 'apiClient'
import { generateOrderNonce } from 'utils/eip712'
import { useMutation } from '@tanstack/react-query'
import { isErrorFromAlias } from '@zodios/core'
import { BaseError as WagmiError, useConfig } from 'wagmi'
import ContractsRegistry from 'contractsRegistry'
import { useWallets } from 'contexts/walletProvider'
import { signOrderCancellation } from 'utils/signingUtils'
import Markets from 'markets'

export function CancelOrderModal({
  order,
  contracts,
  markets,
  isOpen,
  close,
  onClosed
}: {
  order: Order
  contracts: ContractsRegistry
  markets: Markets
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const config = useConfig()
  const wallets = useWallets()

  const cancelOrderMutation = useMutation({
    mutationFn: async () => {
      try {
        const wallet = wallets.primary!
        const nonce = generateOrderNonce()
        const market = markets.getById(order.marketId)
        const signature = await signOrderCancellation(
          wallet,
          config.state.chainId,
          nonce,
          contracts.exchange(config.state.chainId)!.address,
          market,
          order.side,
          order.amount
        )

        if (signature == null) {
          return null
        }

        const payload: CancelOrderRequest = {
          orderId: order.id,
          amount: order.amount,
          marketId: order.marketId,
          side: order.side,
          nonce: nonce,
          signature: signature,
          verifyingChainId: config.state.chainId
        }

        return await apiClient
          .cancelOrder(payload, {
            params: { id: payload.orderId }
          })
          .then(() => payload.orderId)
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'cancelOrder', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: (response) => {
      if (response == null) {
        cancelOrderMutation.reset()
      } else {
        close()
      }
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
                wallets.primary === null ||
                cancelOrderMutation.isPending ||
                cancelOrderMutation.isSuccess
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
