import btc from 'assets/btc.svg'
import eth from 'assets/eth.svg'
import usdc from 'assets/usdc.svg'
import dai from 'assets/dai.svg'
import generic from 'cryptocurrency-icons/svg/color/generic.svg'
import botanix from 'assets/botanix.svg'
import bitlayer from 'assets/bitlayer.svg'
import base from 'assets/base.svg'
import logo from 'assets/logo.svg'
import TradingSymbol from 'tradingSymbol'

interface Props {
  symbol: TradingSymbol
  className?: string
}

export default function SymbolIcon(props: Props) {
  const symbolName = props.symbol.displayName()

  const icon = (function () {
    if (symbolName === 'BTC') {
      return btc
    } else if (symbolName === 'ETH') {
      return eth
    } else if (symbolName === 'USDC') {
      return usdc
    } else if (symbolName === 'DAI') {
      return dai
    } else if (symbolName === 'RING') {
      return logo
    }
    return generic
  })()

  const chainIcon = (function () {
    switch (props.symbol.chainName) {
      case 'Botanix':
        return botanix
      case 'Bitlayer':
        return bitlayer
      case 'Base':
        return base
      default:
        return generic
    }
  })()

  return (
    <span className="relative mt-[-1px] min-w-8 p-1">
      <img src={icon} className={props.className || ''} alt={symbolName} />
      <img
        src={chainIcon}
        className="absolute -bottom-0 right-1 size-1/2"
        alt={props.symbol.chainName}
      />
    </span>
  )
}
