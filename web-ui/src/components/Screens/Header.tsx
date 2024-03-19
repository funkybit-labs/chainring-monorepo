import logo from '../../assets/logo.svg'
import logoName from '../../assets/chainring-logo-name.png'
import { Button } from '../common/Button'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import { useAccount } from 'wagmi'
import { addressDisplay } from '../../utils'

export function Header() {
  const { open: openWalletConnectModal } = useWeb3Modal()
  const account = useAccount()

  return (
    <div className="flex h-20 w-full flex-row place-items-center justify-between bg-neutralGray p-0">
      <span>
        <img className="m-2 inline-block size-16" src={logo} alt="ChainRing" />
        <img
          className="m-2 inline-block aspect-auto h-max w-32 shrink-0 grow-0"
          src={logoName}
          alt="ChainRing"
        />
      </span>
      <span className="m-2">
        {account.isConnected ? (
          <Button
            caption={() => addressDisplay(account.address ?? '0x')}
            onClick={() => openWalletConnectModal({ view: 'Account' })}
          />
        ) : (
          <Button
            caption={() => 'Connect Wallet'}
            onClick={() => openWalletConnectModal({ view: 'Networks' })}
          />
        )}
      </span>
    </div>
  )
}
