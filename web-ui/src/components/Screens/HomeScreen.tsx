import { apiClient, Chain, noAuthApiClient, OrderSide } from 'apiClient'
import BalancesWidget from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { Header, Tab, Widget } from 'components/Screens/Header'
import React, { LegacyRef, useEffect, useMemo, useRef, useState } from 'react'
import OrdersAndTradesWidget from 'components/Screens/HomeScreen/OrdersAndTradesWidget/OrdersAndTradesWidget'
import TradingSymbols from 'tradingSymbols'
import Markets, { Market } from 'markets'
import { PricesWidget } from 'components/Screens/HomeScreen/PricesWidget'
import { useMeasure } from 'react-use'
import { SwapModal } from 'components/Screens/HomeScreen/swap/SwapModal'
import { SwapWidget } from 'components/Screens/HomeScreen/swap/SwapWidget'
import { LimitModal } from 'components/Screens/HomeScreen/swap/LimitModal'
import { useQuery } from '@tanstack/react-query'
import Spinner from 'components/common/Spinner'
import { WebsocketProvider } from 'contexts/websocket'
import { OrderBookWidget } from 'components/Screens/HomeScreen/OrderBookWidget'
import Admin from 'components/Screens/Admin'
import { ConnectedEvmWallet, useWallets } from 'contexts/walletProvider'
import { TestnetChallengeTab } from 'components/Screens/HomeScreen/testnetchallenge/TestnetChallengeTab'
import { TestnetChallengeEnabled } from 'testnetChallenge'
import { useAuth } from 'contexts/auth'

function WebsocketWrapper({ contents }: { contents: JSX.Element }) {
  const wallets = useWallets()
  return <WebsocketProvider wallets={wallets}>{contents}</WebsocketProvider>
}

export const accountConfigQueryKey = ['accountConfiguration']

function HomeScreenContent() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: noAuthApiClient.getConfiguration
  })

  const wallets = useWallets()

  const [selectedMarket, setSelectedMarket] = useState<Market | null>(null)
  const [side, setSide] = useState<OrderSide>(
    (window.sessionStorage.getItem('side') as OrderSide | null) ?? 'Buy'
  )
  const [tab, setTab] = useState<Tab>(
    (window.sessionStorage.getItem('tab') as Tab | null) ??
      (TestnetChallengeEnabled ? 'Testnet Challenge' : 'Swap')
  )
  const [scrollToWidget, setScrollToWidget] = useState<Widget>()

  const {
    exchangeContract,
    chains,
    markets,
    symbols,
    feeRates,
    marketsWithBackToBack
  } = useMemo(() => {
    const config = configQuery.data
    const connectedEvmWallet = wallets.connected.find(
      (cw) => cw.networkType == 'Evm'
    ) as ConnectedEvmWallet | undefined

    const evmChainConfig = config?.chains
      .filter((chain) => chain.networkType === 'Evm')
      .find(
        (chain) =>
          chain.id === (connectedEvmWallet?.chainId ?? config.chains[0]?.id)
      )

    const bitcoinChainConfig = config?.chains.filter(
      (chain) => chain.networkType === 'Bitcoin'
    )[0]

    const exchangeContract = (() => {
      switch (wallets.primary?.networkType) {
        case 'Evm':
          return evmChainConfig
        case 'Bitcoin':
          return bitcoinChainConfig
        case null:
          return null
      }
    })()?.contracts?.find((c) => c.name == 'Exchange')

    const symbols = config ? TradingSymbols.fromConfig(config) : null
    const markets =
      config && symbols ? new Markets(config.markets, symbols, false) : null

    const marketsWithBackToBack =
      config && symbols ? new Markets(config.markets, symbols, true) : null

    const feeRates = config && config.feeRates

    const chains: Chain[] = config?.chains ?? []

    return {
      exchangeContract,
      chains,
      markets,
      symbols,
      feeRates,
      marketsWithBackToBack
    }
  }, [configQuery.data, wallets.connected, wallets.primary])

  useEffect(() => {
    if (markets !== null && selectedMarket == null) {
      const savedMarketId = window.sessionStorage.getItem('market')
      if (savedMarketId) {
        const market = markets.findById(savedMarketId)
        if (market) {
          setSelectedMarket(market)
        } else {
          setSelectedMarket(markets.first())
        }
      } else {
        setSelectedMarket(markets.first())
      }
    }
  }, [markets, selectedMarket])

  const [homeScreenMeasureRef, { width }] = useMeasure()
  const [pricesMeasureRef, { height: pricesMeasuredHeight }] = useMeasure()
  const [balancesMeasureRef, { height: balancesMeasuredHeight }] = useMeasure()
  const [swapMeasureRef, { height: swapMeasuredHeight }] = useMeasure()

  const balancesRef = useRef<HTMLDivElement | null>(null)

  const defaultSide = useMemo(() => {
    if (selectedMarket) {
      const price = selectedMarket.lastPrice.toNumber()
      if (price > 1.1) {
        return 'Buy'
      } else if (price < 0.9) {
        return 'Sell'
      } else {
        return side
      }
    } else {
      return 'Buy'
    }
  }, [side, selectedMarket])

  const [overriddenSide, setOverriddenSide] = useState<OrderSide | undefined>()
  const [showAdmin, setShowAdmin] = useState(false)

  const { isAuthenticated } = useAuth()

  const accountConfigQuery = useQuery({
    queryKey: accountConfigQueryKey,
    queryFn: apiClient.getAccountConfiguration,
    enabled: isAuthenticated
  })

  const isAdmin = useMemo(() => {
    return (
      (accountConfigQuery.data && accountConfigQuery.data.role === 'Admin') ||
      false
    )
  }, [accountConfigQuery.data])

  function saveTab(tab: Tab, widget?: Widget) {
    setTab(tab)
    window.sessionStorage.setItem('tab', tab)
    if (widget) {
      setScrollToWidget(widget)
    }
  }

  useEffect(() => {
    if (scrollToWidget) {
      switch (scrollToWidget) {
        case 'Balances':
          balancesRef.current?.scrollIntoView({ behavior: 'smooth' })
      }
      setScrollToWidget(undefined)
    }
  }, [tab, scrollToWidget])

  return markets && marketsWithBackToBack && feeRates && selectedMarket ? (
    showAdmin ? (
      <Admin onClose={() => setShowAdmin(false)} />
    ) : (
      <>
        <div className="min-h-screen bg-darkBluishGray10">
          <Header
            tab={tab}
            markets={markets}
            isAdmin={isAdmin}
            onTabChange={saveTab}
            onShowAdmin={() => setShowAdmin(true)}
          />

          <div className="mx-4 flex min-h-screen justify-center overflow-auto py-24">
            <div
              className="my-auto laptop:max-w-[1800px]"
              ref={homeScreenMeasureRef as LegacyRef<HTMLDivElement>}
            >
              {tab === 'Swap' && (
                <SwapModal
                  markets={marketsWithBackToBack}
                  walletAddress={wallets.primary?.address}
                  exchangeContractAddress={exchangeContract?.address}
                  feeRates={feeRates}
                  accountConfig={accountConfigQuery.data}
                  onMarketChange={setSelectedMarket}
                  onSideChange={setSide}
                />
              )}
              {tab === 'Limit' && (
                <LimitModal
                  markets={markets}
                  walletAddress={wallets.primary?.address}
                  exchangeContractAddress={exchangeContract?.address}
                  feeRates={feeRates}
                  accountConfig={accountConfigQuery.data}
                  onMarketChange={setSelectedMarket}
                  onSideChange={setSide}
                />
              )}
              {tab === 'Dashboard' && (
                <div className="grid grid-cols-1 gap-4 laptop:grid-cols-3">
                  <div className="col-span-1 space-y-4 laptop:col-span-2">
                    <div ref={pricesMeasureRef as LegacyRef<HTMLDivElement>}>
                      <PricesWidget
                        side={defaultSide}
                        market={selectedMarket}
                        onSideChanged={setOverriddenSide}
                      />
                    </div>
                    {symbols && width >= 1100 && (
                      <div ref={balancesRef as LegacyRef<HTMLDivElement>}>
                        <div
                          ref={balancesMeasureRef as LegacyRef<HTMLDivElement>}
                        >
                          <BalancesWidget
                            walletAddress={wallets.primary?.address}
                            exchangeContractAddress={exchangeContract?.address}
                            symbols={symbols}
                            chains={chains}
                            accountConfig={accountConfigQuery.data}
                          />
                        </div>
                      </div>
                    )}
                  </div>
                  <div className="col-span-1 space-y-4">
                    <div ref={swapMeasureRef as LegacyRef<HTMLDivElement>}>
                      <SwapWidget
                        markets={markets}
                        walletAddress={wallets.primary?.address}
                        exchangeContractAddress={exchangeContract?.address}
                        feeRates={feeRates}
                        onMarketChange={setSelectedMarket}
                        onSideChange={setSide}
                      />
                    </div>
                    {width >= 1100 && (
                      <OrderBookWidget
                        market={selectedMarket}
                        side={overriddenSide ?? defaultSide}
                        height={
                          pricesMeasuredHeight +
                          balancesMeasuredHeight -
                          swapMeasuredHeight
                        }
                      />
                    )}
                  </div>
                  {symbols && width < 1100 && (
                    <div className="col-span-1 space-y-4">
                      <OrderBookWidget
                        market={selectedMarket}
                        side={overriddenSide ?? defaultSide}
                        height={500}
                      />
                      <div ref={balancesRef as LegacyRef<HTMLDivElement>}>
                        <BalancesWidget
                          walletAddress={wallets.primary?.address}
                          exchangeContractAddress={exchangeContract?.address}
                          symbols={symbols}
                          chains={chains}
                        />
                      </div>
                    </div>
                  )}
                  <div className="col-span-1 space-y-4 laptop:col-span-3">
                    <OrdersAndTradesWidget
                      markets={markets}
                      walletAddress={wallets.primary?.address}
                      exchangeContractAddress={exchangeContract?.address}
                    />
                  </div>
                </div>
              )}
              {tab === 'Testnet Challenge' && symbols && (
                <TestnetChallengeTab
                  chains={chains}
                  symbols={symbols}
                  exchangeContract={exchangeContract}
                  accountConfig={accountConfigQuery.data}
                  onChangeTab={saveTab}
                />
              )}
            </div>
          </div>
        </div>
      </>
    )
  ) : (
    <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
      <Spinner />
    </div>
  )
}

export default function HomeScreen() {
  return <WebsocketWrapper contents={<HomeScreenContent />} />
}
