import logo from 'assets/logo.svg'
import { useAccount, useBalance, useReadContract } from 'wagmi'
import { useWeb3Modal } from '@web3modal/wagmi/react'
import Spinner from 'components/Spinner'
import { ExchangeAbi } from 'contracts'
import { useQuery } from '@tanstack/react-query'
import { Address } from 'viem'
import { getConfiguration } from 'ApiClient'

function App() {
  const account = useAccount()

  if (account.isConnected) {
    return <HomeScreen />
  } else {
    return <WelcomeScreen />
  }
}

function WelcomeScreen() {
  const { open: openWalletConnectModal } = useWeb3Modal()

  return (
    <div className="flex h-screen items-center justify-center bg-red-900 py-48">
      <div className="flex flex-col items-center">
        <img className="my-4 inline-block size-36" src={logo} />

        <h1 className="text-6xl font-bold tracking-tight text-gray-100">
          ChainRing
        </h1>

        <p className="mt-4 text-xl text-gray-100">
          The first cross-chain DEX built on Bitcoin
        </p>

        <button
          className="my-8 inline-block rounded-md border border-transparent bg-gray-100 px-8 py-3 text-center font-medium text-black hover:bg-gray-200 focus:outline-none focus:ring-1 focus:ring-inset focus:ring-gray-700"
          onClick={() => openWalletConnectModal({ view: 'Networks' })}
        >
          Connect wallet
        </button>
      </div>
    </div>
  )
}

function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: getConfiguration
  })

  const exchangeContractAddress = (configQuery.data?.contracts || []).find(
    (c) => c.name == 'Exchange'
  )?.address

  return (
    <div className="flex h-screen items-center justify-center bg-red-900 py-48">
      <div className="rounded-lg bg-gray-500/50 p-24 text-white">
        <WalletBalance />
        {exchangeContractAddress && (
          <ExchangeContractVersion address={exchangeContractAddress} />
        )}
      </div>
    </div>
  )
}

function WalletBalance() {
  const account = useAccount()
  const { isPending, error, data } = useBalance({
    address: account.address
  })

  if (isPending) {
    return <Spinner size={12} />
  }

  if (error) {
    return 'Failed to get balance'
  }

  return (
    <div className="flex flex-col items-center">
      <div>Your wallet balance is:</div>
      <div className="my-4 text-xl font-medium">
        {data.formatted} {data.symbol}
      </div>
    </div>
  )
}

function ExchangeContractVersion({ address }: { address: Address }) {
  const {
    isPending,
    error,
    data: version
  } = useReadContract({
    abi: ExchangeAbi,
    address: address,
    functionName: 'getVersion'
  })

  if (isPending) {
    return <Spinner size={12} />
  }

  if (error) {
    return 'Failed to get contract version'
  }

  return (
    <div className="flex flex-col items-center">
      <div>Exchange contract version: {version.toString()}</div>
    </div>
  )
}

export default App
