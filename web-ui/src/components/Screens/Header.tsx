import logo from 'assets/funkybit-orange-logo-name.png'
import { useWallets } from 'contexts/walletProvider'
import { classNames, abbreviatedWalletAddress, uniqueFilter } from 'utils'
import { Button } from 'components/common/Button'
import { Popover, Transition } from '@headlessui/react'
import React, { Fragment, useEffect, useMemo, useState } from 'react'
import Markets from 'markets'
import MenuSvg from 'assets/Menu.svg'
import { FaucetModal } from 'components/Screens/HomeScreen/faucet/FaucetModal'
import faucetIcon from 'assets/faucet.svg'
import { useValidChain } from 'hooks/useValidChain'
import { Address } from 'viem'
import { ConnectWallet } from 'components/Screens/HomeScreen/swap/ConnectWallet'
import { TestnetChallengeEnabled } from 'testnetChallenge'
import { useQuery } from '@tanstack/react-query'
import { apiClient } from 'apiClient'
import { useSwitchToEthChain } from 'utils/switchToEthChain'
import { useConfig } from 'wagmi'

export type Tab = 'Swap' | 'Limit' | 'Dashboard' | 'Testnet Challenge'

export function Header({
  tab,
  markets,
  onTabChange,
  onShowAdmin
}: {
  tab: Tab
  markets: Markets
  onTabChange: (newTab: Tab) => void
  onShowAdmin: () => void
}) {
  const wallets = useWallets()
  const evmConfig = useConfig()
  const [showMenu, setShowMenu] = useState(false)
  const [showFaucetModal, setShowFaucetModal] = useState<boolean>(false)

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

  const accountConfigQuery = useQuery({
    queryKey: ['accountConfiguration'],
    queryFn: apiClient.getAccountConfiguration,
    enabled: wallets.connected.length > 0
  })

  const validChain = useValidChain()
  const switchToEthChain = useSwitchToEthChain()

  const faucetSymbols = useMemo(() => {
    return markets
      .flatMap((m) => [m.baseSymbol, m.quoteSymbol])
      .filter((s) => s.faucetSupported)
      .filter(uniqueFilter)
  }, [markets])

  function walletConnector() {
    return (
      <div className="ml-5 whitespace-nowrap">
        {wallets.connected.length > 0 ? (
          <div className={'relative'}>
            <Popover className="relative">
              {() => (
                <>
                  <Popover.Button className="flex items-center gap-3 overflow-hidden text-ellipsis rounded-[20px] bg-darkBluishGray7 px-4 py-2 text-darkBluishGray1 transition-colors duration-300 ease-in-out hover:bg-primary4 focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray">
                    Connected wallets:
                    {wallets.connected.map((cw) => {
                      return (
                        <img
                          key={cw.networkType}
                          className="inline-block size-5"
                          src={cw.icon}
                          alt={cw.name}
                        />
                      )
                    })}
                  </Popover.Button>
                  <Transition
                    as={Fragment}
                    enter="transition ease-out duration-200"
                    enterFrom="opacity-0 translate-y-1"
                    enterTo="opacity-100 translate-y-0"
                    leave="transition ease-in duration-150"
                    leaveFrom="opacity-100 translate-y-0"
                    leaveTo="opacity-0 translate-y-1"
                  >
                    <Popover.Panel className="absolute right-0 mt-1 max-h-72 w-max min-w-full overflow-auto rounded-[20px] bg-darkBluishGray6 px-4 py-1 shadow-lg ring-1 ring-black/5 focus:outline-none">
                      {wallets.connected.map((connectedWallet) => {
                        return (
                          <div
                            key={connectedWallet.networkType}
                            className="flex items-center gap-2 py-1"
                          >
                            <img
                              key={connectedWallet.networkType}
                              className="inline-block size-5"
                              src={connectedWallet.icon}
                              alt={connectedWallet.name}
                            />
                            {abbreviatedWalletAddress(connectedWallet)}
                            {connectedWallet.networkType === 'Bitcoin' && (
                              <div>
                                <Button
                                  caption={() => {
                                    return <div className="p-1">Disconnect</div>
                                  }}
                                  onClick={() => {
                                    connectedWallet.disconnect()
                                  }}
                                  disabled={false}
                                  style={'normal'}
                                  width={'narrow'}
                                />
                              </div>
                            )}
                            {connectedWallet.networkType === 'Evm' && (
                              <div>
                                {validChain ? (
                                  <Button
                                    caption={() => {
                                      return <div className="p-1">Change</div>
                                    }}
                                    onClick={() => {
                                      connectedWallet.change()
                                    }}
                                    disabled={false}
                                    style={'normal'}
                                    width={'narrow'}
                                  />
                                ) : (
                                  <div className="flex items-center gap-2">
                                    <div className="text-statusRed">
                                      INVALID CHAIN
                                    </div>
                                    <Button
                                      caption={() => {
                                        return <div className="p-1">Switch</div>
                                      }}
                                      onClick={() => {
                                        switchToEthChain(evmConfig.chains[0].id)
                                      }}
                                      disabled={false}
                                      style={'normal'}
                                      width={'narrow'}
                                    />
                                  </div>
                                )}
                              </div>
                            )}
                          </div>
                        )
                      })}
                    </Popover.Panel>
                  </Transition>
                </>
              )}
            </Popover>
          </div>
        ) : tab === 'Dashboard' ? (
          <div className="mt-4">
            <ConnectWallet onSwitchToChain={() => {}} />
          </div>
        ) : (
          <div className="w-[152px]" />
        )}
      </div>
    )
  }

  function faucetButton({ onClick }: { onClick: () => void }) {
    return (
      <span className="mx-5 whitespace-nowrap">
        {faucetSymbols.length > 0 && wallets.connected.length > 0 ? (
          <Button
            style={'normal'}
            width={'normal'}
            caption={() => (
              <span className="flex">
                <img className="h-5 pr-2" src={faucetIcon} alt="Faucet" />
                Faucet
              </span>
            )}
            onClick={() => onClick()}
            disabled={false}
          />
        ) : (
          <></>
        )}
      </span>
    )
  }

  function navigate(id: string) {
    onTabChange('Dashboard')
    window.location.hash = id
    window.scrollBy({ top: -80, behavior: 'smooth' })
    setShowMenu(false)
  }
  const isAdmin = useMemo(() => {
    return accountConfigQuery.data && accountConfigQuery.data.role === 'Admin'
  }, [accountConfigQuery.data])

  return (
    <>
      <div className="fixed z-50 grid h-20 w-full grid-cols-[max-content_1fr_max-content] place-items-center bg-darkBluishGray10 p-0 text-sm text-darkBluishGray1">
        <span className="justify-self-start">
          <img
            className={classNames(
              'm-6 hidden h-10 narrow:inline-block',
              isAdmin && 'cursor-pointer'
            )}
            src={logo}
            alt="funkybit"
            onClick={() => {
              if (isAdmin) {
                onShowAdmin()
              }
            }}
          />
          <div className="m-4 inline-block cursor-pointer rounded-sm bg-darkBluishGray7 p-2 narrow:hidden">
            <img
              className="inline-block h-4"
              src={MenuSvg}
              alt="Menu"
              onClick={() => setShowMenu(true)}
            />
          </div>
        </span>
        <div className="cursor-pointer space-x-4 text-[16px]">
          {(
            [
              ...(TestnetChallengeEnabled ? ['Testnet Challenge'] : []),
              'Swap',
              'Limit',
              'Dashboard'
            ] as Tab[]
          ).map((t) => (
            <span
              key={t}
              className={classNames(
                'border-b-2 pb-2 transition-colors',
                tab === t
                  ? 'text-statusOrange'
                  : 'text-darkBluishGray3 hover:text-white'
              )}
              onClick={() => {
                onTabChange(t)
              }}
            >
              <span className="px-2">{t}</span>
            </span>
          ))}
        </div>
        <div className="flex space-x-2 narrow:mr-0">
          <div className="hidden narrow:inline-block">{walletConnector()}</div>
          <div className="hidden narrow:inline-block">
            {faucetButton({ onClick: () => setShowFaucetModal(true) })}
          </div>
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
                alt="funkybit"
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
              <div>
                <div className="mb-2">{walletConnector()}</div>
                <div className="mb-8">
                  {faucetButton({
                    onClick: () => {
                      setShowMenu(false)
                      setShowFaucetModal(true)
                    }
                  })}
                </div>
              </div>
            </div>
          </div>
        </>
      )}
      {faucetSymbols.length > 0 &&
        wallets.connected.length > 0 &&
        showFaucetModal && (
          <div className="fixed">
            <FaucetModal
              isOpen={showFaucetModal}
              walletAddress={wallets.primary!.address as Address}
              symbols={faucetSymbols}
              close={() => setShowFaucetModal(false)}
            />
          </div>
        )}
    </>
  )
}
