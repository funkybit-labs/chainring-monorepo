import { useWeb3Modal } from '@web3modal/wagmi/react'
import logo from 'assets/logo.svg'

export default function WelcomeScreen() {
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