import { createRoot } from 'react-dom/client'
import 'tailwindcss/tailwind.css'
import App from 'components/App'
import { initializeWagmiConfig, wagmiConfig } from 'wagmiConfig'
import { WagmiProvider } from 'wagmi'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import Spinner from 'components/common/Spinner'
import React, { useEffect, useState } from 'react'
import { useMaintenance } from 'apiClient'

const container = document.getElementById('root') as HTMLDivElement
const root = createRoot(container)
const queryClient = new QueryClient()

const Root = () => {
  const maintenance = useMaintenance()
  const [, setIsConfigInitialized] = useState(false)

  useEffect(() => {
    const initConfig = async () => {
      await initializeWagmiConfig()
      setIsConfigInitialized(true)
    }

    initConfig()
  }, [])

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
        <WagmiProvider config={wagmiConfig}>
          <QueryClientProvider client={queryClient}>
            <App />
          </QueryClientProvider>
        </WagmiProvider>
      ) : (
        <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
          <Spinner />
        </div>
      )}
    </>
  )
}

root.render(<Root />)
