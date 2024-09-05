import { Button } from 'components/common/Button'
import React, { useState } from 'react'
import { useConfig } from 'wagmi'
import { useValidChain } from 'hooks/useValidChain'
import { Modal } from 'components/common/Modal'
import { useWallet } from 'contexts/walletProvider'

type Props = {
  onSwitchToChain: (chainId: number) => Promise<void> | void
}
export const bitcoinEnabled = import.meta.env.ENV_ENABLE_BITCOIN

export function ConnectWallet({ onSwitchToChain }: Props) {
  const validChain = useValidChain()
  const wallet = useWallet()
  const evmConfig = useConfig()

  const connectedWithInvalidChain =
    wallet.evmAccount?.isConnected && !validChain

  const [showWalletCategorySelection, setShowWalletCategorySelection] =
    useState(false)

  return (
    <>
      <div>
        <Button
          caption={() =>
            connectedWithInvalidChain ? 'Invalid Chain' : 'Connect Wallet'
          }
          onClick={() => {
            if (connectedWithInvalidChain) {
              onSwitchToChain(evmConfig.chains[0].id)
            } else {
              switch (wallet.primaryCategory) {
                case 'bitcoin':
                  wallet.connect('evm')
                  break
                case 'evm':
                  wallet.connect(bitcoinEnabled ? 'bitcoin' : 'evm')
                  break
                case 'none':
                  if (bitcoinEnabled) {
                    setShowWalletCategorySelection(true)
                  } else {
                    wallet.connect('evm')
                  }
                  break
              }
            }
          }}
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
