import logo from 'assets/logo-name.svg'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import { useAccount, useSwitchChain } from 'wagmi'
import { addressDisplay } from 'utils'
import { Button } from 'components/common/Button'
import React, { useEffect, useState } from 'react'
import { MarketSelector } from 'components/Screens/HomeScreen/MarketSelector'
import Markets, { Market } from 'markets'
import { useMaintenance } from 'apiClient'
import { chain } from 'wagmiConfig'

export function Header({
  markets,
  selectedMarket,
  onMarketChange
}: {
  markets: Markets
  selectedMarket: Market | null
  onMarketChange: (newValue: Market) => void
}) {
  const { open: openWalletConnectModal } = useWeb3Modal()
  const account = useAccount()
  const [name, setName] = useState<string>()
  const [icon, setIcon] = useState<string>()
  const maintenance = useMaintenance()
  const { switchChain } = useSwitchChain()

  useEffect(() => {
    if (account.isConnected && account.connector) {
      setIcon(account.connector.icon)
      setName(account.connector.name)

      switchChain({
        addEthereumChainParameter: {
          chainName: chain.name,
          nativeCurrency: chain.nativeCurrency,
          rpcUrls: chain.rpcUrls.default.http,
          blockExplorerUrls: [chain.blockExplorers.default.url]
        },
        chainId: chain.id
      })
    }
  }, [account.isConnected, account.connector, switchChain])

  return (
    <>
      <div className="fixed z-50 flex h-20 w-full flex-row place-items-center justify-between bg-darkBluishGray10 p-0 text-sm text-darkBluishGray1">
        <span>
          <img className="m-6 inline-block h-10 " src={logo} alt="ChainRing" />
        </span>

        <div className="flex">
          {selectedMarket && (
            <div className="flex items-center gap-1">
              Market:
              <MarketSelector
                markets={markets}
                selected={selectedMarket}
                onChange={onMarketChange}
              />
            </div>
          )}

          <span className="mx-5">
            {account.isConnected ? (
              <Button
                style={'normal'}
                caption={() => (
                  <span>
                    {icon && (
                      <img
                        className="mr-2 inline-block size-5"
                        src={icon}
                        alt={name ?? ''}
                      />
                    )}
                    {addressDisplay(account.address ?? '0x')}
                  </span>
                )}
                onClick={() => openWalletConnectModal({ view: 'Account' })}
                disabled={false}
                primary={false}
              />
            ) : (
              <Button
                style={'normal'}
                caption={() => <>Connect Wallet</>}
                onClick={() => openWalletConnectModal({ view: 'Connect' })}
                primary={true}
                disabled={false}
              />
            )}
          </span>
        </div>
      </div>
      {maintenance && (
        <div className="fixed z-50 flex w-full flex-row place-items-center justify-center bg-red p-0 text-white opacity-80">
          <span className="animate-bounce">
            ChainRing is currently undergoing maintenance, we&apos;ll be back
            soon.
          </span>
        </div>
      )}
    </>
  )
}
