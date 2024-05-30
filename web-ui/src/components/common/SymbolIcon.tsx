import btc from 'assets/btc.svg'
import eth from 'assets/eth.svg'
import usdc from 'assets/usdc.svg'
import dai from 'assets/dai.svg'
import generic from 'cryptocurrency-icons/svg/color/generic.svg'
import botanix from 'assets/botanix.svg'
import bitlayer from 'assets/bitlayer.svg'
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
    <span className="relative mt-[-1px] p-1">
      <img src={icon} className={props.className || ''} alt={symbolName} />
      <img src={chainIcon} className="absolute -bottom-1 right-1 size-4" />
    </span>
  )
}
