import logo from 'assets/logo.svg'
import logoName from 'assets/chainring-logo-name.png'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import { useAccount } from 'wagmi'
import { addressDisplay } from 'utils'
import { Button } from 'components/common/Button'
import { useEffect, useState } from 'react'

export function Header() {
  const { open: openWalletConnectModal } = useWeb3Modal()
  const account = useAccount()
  const [name, setName] = useState<string>()
  const [icon, setIcon] = useState<string>()

  useEffect(() => {
    if (account.isConnected && account.connector) {
      setIcon(account.connector.icon)
      setName(account.connector.name)
    }
  }, [account.isConnected, account.connector])

  return (
    <div className="fixed flex h-20 w-full flex-row place-items-center justify-between bg-neutralGray p-0">
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
            caption={() => (
              <span>
                {icon && (
                  <img
                    className="mr-2 inline-block size-8"
                    src={icon}
                    alt={name ?? ''}
                  />
                )}
                {addressDisplay(account.address ?? '0x')}
              </span>
            )}
            onClick={() => openWalletConnectModal({ view: 'Account' })}
            disabled={false}
          />
        ) : (
          <Button
            caption={() => <>Connect Wallet</>}
            onClick={() => openWalletConnectModal({ view: 'Networks' })}
            disabled={false}
          />
        )}
      </span>
    </div>
  )
}
