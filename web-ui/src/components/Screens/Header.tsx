import logo from 'assets/funkybit-orange-logo-name.png'
import { useWallet } from 'contexts/walletProvider'
import {
  evmAddressDisplay,
  classNames,
  uniqueFilter,
  bitcoinAddressDisplay
} from 'utils'
import { Button } from 'components/common/Button'
import React, { useEffect, useMemo, useState } from 'react'
import Markets from 'markets'
import Menu from 'assets/Menu.svg'
import { FaucetModal } from 'components/Screens/HomeScreen/faucet/FaucetModal'
import faucetIcon from 'assets/faucet.svg'
import { useValidChain } from 'hooks/useValidChain'
import { Address } from 'viem'
import BtcSvg from 'assets/btc.svg'
import { ConnectWallet } from 'components/Screens/HomeScreen/swap/ConnectWallet'
import { TestnetChallengeEnabled } from 'testnetChallenge'
import { useQuery } from '@tanstack/react-query'
import { apiClient } from 'apiClient'

export type Tab = 'Swap' | 'Limit' | 'Dashboard' | 'Testnet Challenge'

export function Header({
  initialTab,
  markets,
  onTabChange,
  onShowAdmin
}: {
  initialTab: Tab
  markets: Markets
  onTabChange: (newTab: Tab) => void
  onShowAdmin: () => void
}) {
  const wallet = useWallet()
  const [name, setName] = useState<string>()
  const [icon, setIcon] = useState<string>()
  const [showMenu, setShowMenu] = useState(false)
  const [showFaucetModal, setShowFaucetModal] = useState<boolean>(false)
  const [tab, setTab] = useState<Tab>(initialTab)
  const [showDisconnect, setShowDisconnect] = useState(false)

  useEffect(() => {
    switch (wallet.primaryCategory) {
      case 'evm':
        if (wallet.evmAccount?.isConnected && wallet.evmAccount.connector) {
          setIcon(wallet.evmAccount.connector.icon)
          setName(wallet.evmAccount.connector.name)
        }
        break
      case 'bitcoin':
        if (wallet.bitcoinAccount?.address !== undefined) {
          setIcon(BtcSvg)
          setName('Bitcoin')
        }
        break
      case 'none':
        setIcon(undefined)
        setName(undefined)
        break
    }
  }, [wallet])

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
    queryFn: apiClient.getAccountConfiguration
  })

  const validChain = useValidChain()

  const faucetSymbols = useMemo(() => {
    return markets
      .flatMap((m) => [m.baseSymbol, m.quoteSymbol])
      .filter((s) => s.faucetSupported)
      .filter(uniqueFilter)
  }, [markets])

  function walletConnector() {
    return (
      <div className="ml-5 whitespace-nowrap">
        {wallet.primaryCategory !== 'none' ? (
          <div className={'relative'}>
            <Button
              style={validChain ? 'normal' : 'warning'}
              width={'normal'}
              tooltip={validChain ? undefined : 'INVALID CHAIN'}
              caption={() => (
                <span>
                  {icon && (
                    <img
                      className="mr-2 inline-block size-5"
                      src={icon}
                      alt={name ?? ''}
                    />
                  )}
                  {wallet.primaryCategory === 'evm'
                    ? evmAddressDisplay(wallet.primaryAddress ?? '0x')
                    : bitcoinAddressDisplay(wallet.primaryAddress)}
                </span>
              )}
              onClick={() => {
                if (wallet.primaryCategory === 'evm') {
                  wallet.changeAccount()
                } else if (wallet.primaryCategory === 'bitcoin') {
                  setShowDisconnect(true)
                }
              }}
              disabled={false}
              primary={false}
            />
            {showDisconnect && (
              <div className={'absolute'}>
                <Button
                  caption={() => {
                    return <>Disconnect</>
                  }}
                  onClick={() => {
                    setShowDisconnect(false)
                    wallet.disconnect()
                  }}
                  disabled={false}
                  style={'normal'}
                  width={'normal'}
                />
              </div>
            )}
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
        {faucetSymbols.length > 0 && wallet.primaryCategory !== 'none' ? (
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
    setTab('Dashboard')
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
      <div className="fixed z-50 grid h-20 w-full grid-cols-[max-content_1fr_max-content] place-items-center overflow-x-scroll bg-darkBluishGray10 p-0 text-sm text-darkBluishGray1">
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
              src={Menu}
              alt="Menu"
              onClick={() => setShowMenu(true)}
            />
          </div>
        </span>
        <div className="cursor-pointer space-x-4 text-[16px]">
          {(
            [
              'Swap',
              'Limit',
              'Dashboard',
              ...(TestnetChallengeEnabled && wallet.primaryCategory !== 'none'
                ? ['Testnet Challenge']
                : [])
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
                setTab(t)
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
        wallet.primaryCategory !== 'none' &&
        showFaucetModal && (
          <div className="fixed">
            <FaucetModal
              isOpen={showFaucetModal}
              walletAddress={wallet.primaryAddress! as Address}
              symbols={faucetSymbols}
              close={() => setShowFaucetModal(false)}
            />
          </div>
        )}
    </>
  )
}
