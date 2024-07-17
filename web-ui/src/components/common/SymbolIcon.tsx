import generic from 'cryptocurrency-icons/svg/color/generic.svg'
import botanix from 'assets/botanix.svg'
import bitlayer from 'assets/bitlayer.svg'
import base from 'assets/base.svg'
import TradingSymbol from 'tradingSymbol'

interface Props {
  symbol: TradingSymbol
  className?: string
}

function getSymbolAndChainIcons(symbol: TradingSymbol): {
  chainIcon: string
  icon: string
} {
  const chainIcon = (() => {
    switch (symbol.chainName) {
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

  return { icon: symbol.iconUrl, chainIcon }
}

export default function SymbolIcon(props: Props) {
  const symbolName = props.symbol.displayName()
  const { icon, chainIcon } = getSymbolAndChainIcons(props.symbol)

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

export function SymbolIconSVG(props: Props) {
  const { icon, chainIcon } = getSymbolAndChainIcons(props.symbol)

  return (
    <g className={props.className}>
      <image href={icon} width="24" height="24" />
      <image href={chainIcon} width="12" height="12" x="16" y="12" />
    </g>
  )
}
