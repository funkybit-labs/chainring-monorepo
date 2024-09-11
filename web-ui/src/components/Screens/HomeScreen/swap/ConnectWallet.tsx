import { Button } from 'components/common/Button'
import React, { useState } from 'react'
import { useConfig } from 'wagmi'
import { useValidChain } from 'hooks/useValidChain'
import { Modal } from 'components/common/Modal'
import { useWallets } from 'contexts/walletProvider'
import { bitcoinEnabled } from 'contexts/bitcoin'

type Props = {
  onSwitchToChain: (chainId: number) => Promise<void> | void
}

export function ConnectWallet({ onSwitchToChain }: Props) {
  const validChain = useValidChain()
  const wallets = useWallets()
  const evmConfig = useConfig()

  const connectedWithInvalidChain = wallets.isConnected('Evm') && !validChain

  const [showWalletCategorySelection, setShowWalletCategorySelection] =
    useState(false)

  return (
    <>
      <div>
        <Button
          caption={() => {
            let missingConnection = ''
            switch (wallets.primary?.networkType) {
              case 'Bitcoin':
                missingConnection = 'EVM '
                break
              case 'Evm':
                missingConnection = bitcoinEnabled ? 'Bitcoin ' : 'EVM '
                break
            }

            return connectedWithInvalidChain
              ? 'Invalid Chain'
              : `Connect ${missingConnection}Wallet`
          }}
          onClick={() => {
            if (connectedWithInvalidChain) {
              onSwitchToChain(evmConfig.chains[0].id)
            } else {
              switch (wallets.primary?.networkType) {
                case 'Bitcoin':
                  wallets.connect('Evm')
                  break
                case 'Evm':
                  wallets.connect(bitcoinEnabled ? 'Bitcoin' : 'Evm')
                  break
                case undefined:
                  // For now we always have them connect EVM first
                  //if (bitcoinEnabled) {
                  //  setShowWalletCategorySelection(true)
                  //} else {
                  wallets.connect('Evm')
                  //}
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
                wallets.connect('Evm')
                setShowWalletCategorySelection(false)
              }}
              disabled={false}
              primary={true}
              width={'full'}
              style={'normal'}
            />
            <Button
              caption={() => 'Bitcoin Wallets'}
              onClick={() => {
                wallets.connect('Bitcoin')
                setShowWalletCategorySelection(false)
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
