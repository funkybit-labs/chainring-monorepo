import logo from 'assets/logo-name.svg'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import { useAccount } from 'wagmi'
import { addressDisplay } from 'utils'
import { Button } from 'components/common/Button'
import React, { useEffect, useState } from 'react'
import { MarketSelector } from 'components/Screens/HomeScreen/MarketSelector'
import Markets, { Market } from 'markets'
import { useMaintenance } from 'apiClient'
import Menu from 'assets/Menu.svg'

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
  const [showMenu, setShowMenu] = useState(false)

  useEffect(() => {
    if (account.isConnected && account.connector) {
      setIcon(account.connector.icon)
      setName(account.connector.name)
    }
  }, [account.isConnected, account.connector])

  useEffect(() => {
    function escapeHandler(ev: KeyboardEvent) {
      if (ev.key === 'Escape') {
        setShowMenu(false)
      }
    }
    if (showMenu) {
      document.addEventListener('keydown', escapeHandler, false)
    }
    return () => {
      document.removeEventListener('keydown', escapeHandler, false)
    }
  }, [showMenu])

  function walletConnector() {
    return (
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
    )
  }

  function navigate(id: string) {
    window.location.hash = id
    window.scrollBy({ top: -80, behavior: 'smooth' })
    setShowMenu(false)
  }

  return (
    <>
      <div className="fixed z-50 flex h-20 w-full flex-row place-items-center justify-between bg-darkBluishGray10 p-0 text-sm text-darkBluishGray1">
        <span>
          <img
            className="m-6 hidden h-10 narrow:inline-block"
            src={logo}
            alt="ChainRing"
          />
          <div className="m-4 inline-block cursor-pointer rounded-sm bg-darkBluishGray7 p-2 narrow:hidden">
            <img
              className="inline-block h-4"
              src={Menu}
              alt="Menu"
              onClick={() => setShowMenu(true)}
            />
          </div>
        </span>

        <div className="mr-4 flex narrow:mr-0">
          {selectedMarket && (
            <div className="flex items-center gap-1">
              <span className="mr-2 hidden narrow:inline">Market:</span>
              <MarketSelector
                markets={markets}
                selected={selectedMarket}
                onChange={onMarketChange}
              />
            </div>
          )}

          <div className="hidden narrow:inline-block">{walletConnector()}</div>
        </div>
      </div>
      {showMenu && (
        <>
          <div className="fixed left-0 top-0 z-50 h-screen w-screen bg-black opacity-70" />
          <div className="fixed left-0 top-0 z-50 h-screen bg-darkBluishGray9 px-8">
            <div className="flex h-full flex-col place-content-between">
              <img
                className="m-6 h-14 cursor-pointer"
                src={logo}
                alt="ChainRing"
                onClick={() => setShowMenu(false)}
              />
              <div className="m-6 cursor-pointer space-y-8 text-lg text-white">
                <div onClick={() => navigate('prices')}>Dashboard</div>
                <div onClick={() => navigate('order-ticket')}>Trade</div>
                <div onClick={() => navigate('order-book')}>Order Book</div>
                <div onClick={() => navigate('balances')}>Balances</div>
                <div onClick={() => navigate('orders-and-trades')}>
                  Orders & Trades
                </div>
              </div>
              <div className="mb-8">{walletConnector()}</div>
            </div>
          </div>
        </>
      )}
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
