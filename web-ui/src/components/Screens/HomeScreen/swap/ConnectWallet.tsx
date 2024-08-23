import { Button } from 'components/common/Button'
import React, { useState } from 'react'
import { useConfig } from 'wagmi'
import { useValidChain } from 'hooks/useValidChain'
import { Modal } from 'components/common/Modal'
import { useWallet } from 'contexts/walletProvider'

type Props = {
  onSwitchToChain: (chainId: number) => Promise<void> | void
}
const bitcoinEnabled = import.meta.env.ENV_ENABLE_BITCOIN

export function ConnectWallet({ onSwitchToChain }: Props) {
  const validChain = useValidChain()
  const wallet = useWallet()
  const config = useConfig()

  const connectedWithInvalidChain =
    wallet.evmAccount?.isConnected && !validChain

  const [showWalletCategorySelection, setShowWalletCategorySelection] =
    useState(false)

  return (
    <>
      <div className="mt-4">
        <Button
          caption={() =>
            connectedWithInvalidChain ? 'Invalid Chain' : 'Connect Wallet'
          }
          onClick={() =>
            connectedWithInvalidChain
              ? onSwitchToChain(config.chains[0].id)
              : bitcoinEnabled
                ? setShowWalletCategorySelection(true)
                : wallet.connect('evm')
          }
          disabled={false}
          primary={true}
          width={'full'}
          style={connectedWithInvalidChain ? 'warning' : 'normal'}
        />
      </div>
      {showWalletCategorySelection && (
        <Modal
          isOpen={showWalletCategorySelection}
          close={() => setShowWalletCategorySelection(false)}
          onClosed={() => {}}
          title={'Select Wallet Type'}
        >
          <div className="flex flex-col gap-8">
            <Button
              caption={() => 'EVM Wallets'}
              onClick={() => {
                wallet.connect('evm')
              }}
              disabled={false}
              primary={true}
              width={'full'}
              style={'normal'}
            />
            <Button
              caption={() => 'Bitcoin Wallets'}
              onClick={() => {
                wallet.connect('bitcoin')
              }}
              disabled={false}
              primary={true}
              width={'full'}
              style={'normal'}
            />
          </div>
        </Modal>
      )}
    </>
  )
}
