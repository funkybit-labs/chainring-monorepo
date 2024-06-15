import { Button } from 'components/common/Button'
import React from 'react'
import { useAccount, useConfig } from 'wagmi'
import { useValidChain } from 'hooks/useValidChain'
import { useWeb3Modal } from '@web3modal/wagmi/react'

type Props = {
  onSwitchToChain: (chainId: number) => Promise<void> | void
}

export function ConnectWallet({ onSwitchToChain }: Props) {
  const validChain = useValidChain()
  const wallet = useAccount()
  const { open: openWalletConnectModal } = useWeb3Modal()
  const config = useConfig()

  const connectedWithInvalidChain = wallet.isConnected && !validChain

  return (
    <div className="mt-4">
      <Button
        caption={() =>
          connectedWithInvalidChain ? 'Invalid Chain' : 'Connect Wallet'
        }
        onClick={() =>
          connectedWithInvalidChain
            ? onSwitchToChain(config.chains[0].id)
            : openWalletConnectModal({ view: 'Connect' })
        }
        disabled={false}
        primary={true}
        width={'full'}
        style={connectedWithInvalidChain ? 'warning' : 'normal'}
      />
    </div>
  )
}
