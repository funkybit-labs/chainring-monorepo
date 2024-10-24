import { createRoot } from 'react-dom/client'
import 'tailwindcss/tailwind.css'
import { initializeWagmiConfig, wagmiConfig } from 'wagmiConfig'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import React, { useEffect, useState } from 'react'
import { useAccessRestriction } from 'apiClient'
import { WalletProvider } from 'contexts/walletProvider'
import { testnetChallengeInviteCodeKey } from 'components/Screens/HomeScreen/testnetchallenge/TestnetChallengeTab'
import { AuthProvider } from 'contexts/auth'
import App from 'components/App'
import Spinner from 'components/common/Spinner'
import OauthRelay, { OAuthRelayFlow } from 'components/Screens/OauthRelay'
import {
  getOauthRelayAuthFlow,
  storeOauthRelayAuthTokenAndFlow
} from 'contexts/oauthRelayAuth'

const container = document.getElementById('root') as HTMLDivElement
const root = createRoot(container)
const queryClient = new QueryClient()

const Root = () => {
  const accessRestriction = useAccessRestriction()
  const [, setIsConfigInitialized] = useState(false)
  const [oauthRelayFlow, setOauthRelayFlow] = useState<OAuthRelayFlow>()

  useEffect(() => {
    const urlPath = window.location.pathname
    if (urlPath.includes('/oauth-relay')) {
      const query = new URLSearchParams(window.location.search)
      const flow = query.get('flow') as OAuthRelayFlow
      storeOauthRelayAuthTokenAndFlow(query.get('token') ?? '', flow)
      setOauthRelayFlow(flow)
    } else if (
      urlPath.search('/\\w+-callback') > -1 &&
      getOauthRelayAuthFlow() !== undefined
    ) {
      setOauthRelayFlow(getOauthRelayAuthFlow())
    } else {
      if (urlPath.includes('/invite/')) {
        const code = urlPath.split('/').pop()
        if (code) {
          localStorage.setItem(testnetChallengeInviteCodeKey, code)
        }
      }

      const initConfig = async () => {
        await initializeWagmiConfig()
        setIsConfigInitialized(true)
      }

      initConfig()
    }
  }, [])

  if (accessRestriction == 'geoBlocked') {
    return (
      <div className="flex h-screen items-center justify-center bg-darkBluishGray10 text-white">
        <div className="w-2/3 text-center ">
          <h1 className="text-3xl font-bold">
            Uh-oh! funkybit isn&apos;t feeling so funky where you&apos;re at...
          </h1>
          <p className="mt-4 text-lg">
            Whether you&apos;re tuning in from North Korea, the great state of
            New York, or another place where fun is restricted — access is
            currently unavailable. But hey, at least you&apos;ve got pizza
            (unless you&apos;re in North Korea... then we&apos;re really sorry)!
          </p>
        </div>
      </div>
    )
  }

  return (
    <>
      {accessRestriction == 'underMaintenance' && (
        <div className="fixed z-[100] flex w-full flex-row place-items-center justify-center bg-red p-0 text-white opacity-80">
          <span className="animate-bounce">
            funkybit is currently undergoing maintenance, we&apos;ll be back
            soon.
          </span>
        </div>
      )}
      {accessRestriction == 'requestLimitExceeded' && (
        <div className="fixed z-[100] flex w-full flex-row place-items-center justify-center bg-red p-0 text-white opacity-80">
          <span className="animate-bounce">
            You&apos;ve hit the rate limit! Please slow down and try again soon.
          </span>
        </div>
      )}
      {oauthRelayFlow !== undefined ? (
        <QueryClientProvider client={queryClient}>
          <OauthRelay flow={oauthRelayFlow} />
        </QueryClientProvider>
      ) : wagmiConfig ? (
        <WalletProvider>
          <AuthProvider>
            <QueryClientProvider client={queryClient}>
              <App />
            </QueryClientProvider>
          </AuthProvider>
        </WalletProvider>
      ) : (
        <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
          <Spinner />
        </div>
      )}
    </>
  )
}

root.render(<Root />)
