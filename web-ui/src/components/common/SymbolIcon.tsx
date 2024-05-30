import btc from 'cryptocurrency-icons/svg/color/btc.svg'
import eth from 'cryptocurrency-icons/svg/color/eth.svg'
import usdc from 'cryptocurrency-icons/svg/color/usdc.svg'
import dai from 'cryptocurrency-icons/svg/color/dai.svg'
import generic from 'cryptocurrency-icons/svg/color/generic.svg'
import botanix from 'assets/botanix.png'
import bitlayer from 'assets/bitlayer.png'
import TradingSymbol from 'tradingSymbol'

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

  const chainIcon = (function () {
    if (symbolName.endsWith('2')) {
      return botanix
    } else {
      return bitlayer
    }
  })()

  return (
    <span className="relative p-1">
      <img src={icon} className={props.className || ''} alt={symbolName} />
      <img src={chainIcon} className="absolute -bottom-1 right-1 size-4" />
    </span>
  )
}
