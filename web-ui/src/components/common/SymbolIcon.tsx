import { TradingSymbol } from 'apiClient'
import btc from 'cryptocurrency-icons/svg/color/btc.svg'
import eth from 'cryptocurrency-icons/svg/color/eth.svg'
import usdc from 'cryptocurrency-icons/svg/color/usdc.svg'
import dai from 'cryptocurrency-icons/svg/color/dai.svg'
import generic from 'cryptocurrency-icons/svg/color/generic.svg'

interface Props {
  symbol: TradingSymbol | string
  className?: string
}

export default function SymbolIcon(props: Props) {
  const symbolName =
    typeof props.symbol === 'string' ? props.symbol : props.symbol.name

  const icon = (function () {
    if (symbolName.includes('BTC')) {
      return btc
    } else if (symbolName.includes('ETH')) {
      return eth
    } else if (symbolName.includes('USDC')) {
      return usdc
    } else if (symbolName.includes('DAI')) {
      return dai
    }
    return generic
  })()

  return <img src={icon} className={props.className || ''} alt={symbolName} />
}
