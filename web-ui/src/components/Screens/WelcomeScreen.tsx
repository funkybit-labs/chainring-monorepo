import { useWeb3Modal } from '@web3modal/wagmi/react'
import logo from 'assets/logo.svg'

export default function WelcomeScreen() {
  const { open: openWalletConnectModal } = useWeb3Modal()

  return (
    <div className="bg-red-900 flex h-screen items-center justify-center py-48">
      <div className="flex flex-col items-center">
        <img className="my-4 inline-block size-36" src={logo} />

        <h1 className="text-gray-100 text-6xl font-bold tracking-tight">
          ChainRing
        </h1>

        <p className="text-gray-100 mt-4 text-xl">
          The first cross-chain DEX built on Bitcoin
        </p>

        <button
          className="border-transparent bg-gray-100 text-black hover:bg-gray-200 focus:ring-gray-700 my-8 inline-block rounded-md border px-8 py-3 text-center font-medium focus:outline-none focus:ring-1 focus:ring-inset"
          onClick={() => openWalletConnectModal({ view: 'Networks' })}
        >
          Connect wallet
        </button>
      </div>
    </div>
  )
}
