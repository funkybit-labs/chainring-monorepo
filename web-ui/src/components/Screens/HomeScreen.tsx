import { useQuery } from '@tanstack/react-query'
import { getConfiguration } from 'ApiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'
import { Header } from './Header'
import { OrderBook } from 'components/Screens/HomeScreen/OrderBook'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: getConfiguration
  })

  const wallet = useAccount()
  const walletAddress = wallet.address

  const exchangeContractAddress = (configQuery.data?.contracts || []).find(
    (c) => c.name == 'Exchange'
  )?.address

  return (
    <div className="h-screen bg-gradient-to-b from-lightBackground to-darkBackground">
      <Header />
      <div className="flex h-screen w-screen flex-col">
        <div className="flex px-4 pt-24">
          <div className="flex flex-col gap-4">
            <OrderBook />
          </div>
        </div>
        <div className="flex px-4 pt-24">
          <div className="flex flex-col items-center gap-4">
            {walletAddress && exchangeContractAddress && (
              <>
                <Balances
                  exchangeContractAddress={exchangeContractAddress}
                  walletAddress={walletAddress}
                  erc20TokenContracts={configQuery.data?.erc20Tokens || []}
                />
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
