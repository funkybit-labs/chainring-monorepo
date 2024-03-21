import { useQuery } from '@tanstack/react-query'
import { getConfiguration } from 'ApiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'
import { Header } from './Header'
import { OrderBook } from './HomeScreen/OrderBook'
import Trade from './HomeScreen/Trade'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: getConfiguration
  })

  const wallet = useAccount()
  const walletAddress = wallet.address

  const chainConfig = configQuery.data?.chains.find(
    (chain) => chain.id == wallet.chainId
  )
  const exchangeContract = chainConfig?.contracts?.find(
    (c) => c.name == 'Exchange'
  )

  const nativeToken = chainConfig?.nativeToken
  const erc20Tokens = chainConfig?.erc20Tokens

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
            {walletAddress &&
              exchangeContract &&
              erc20Tokens &&
              nativeToken && (
                <>
                  <Trade baseSymbol={'BTC'} quoteSymbol={'ETH'} />
                  <Balances
                    walletAddress={walletAddress}
                    exchangeContractAddress={exchangeContract.address}
                    nativeToken={nativeToken}
                    erc20Tokens={erc20Tokens}
                  />
                </>
              )}
          </div>
        </div>
      </div>
    </div>
  )
}
