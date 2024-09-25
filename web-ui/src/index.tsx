import { createRoot } from 'react-dom/client'
import 'tailwindcss/tailwind.css'
import App from 'components/App'
import { initializeWagmiConfig, wagmiConfig } from 'wagmiConfig'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Spinner from 'components/common/Spinner'
import React, { useEffect, useState } from 'react'
import { useAccessRestriction } from 'apiClient'
import { WalletProvider } from 'contexts/walletProvider'
import { testnetChallengeInviteCodeKey } from 'components/Screens/HomeScreen/testnetchallenge/TestnetChallengeTab'
import { AuthProvider } from 'contexts/auth'

const container = document.getElementById('root') as HTMLDivElement
const root = createRoot(container)
const queryClient = new QueryClient()

const Root = () => {
  const [maintenance, restricted] = useAccessRestriction()
  const [, setIsConfigInitialized] = useState(false)

  useEffect(() => {
    const urlPath = window.location.pathname
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
  }, [])

  if (restricted) {
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
      {maintenance && (
        <div className="fixed z-[100] flex w-full flex-row place-items-center justify-center bg-red p-0 text-white opacity-80">
          <span className="animate-bounce">
            funkybit is currently undergoing maintenance, we&apos;ll be back
            soon.
          </span>
        </div>
      )}
      {wagmiConfig ? (
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
