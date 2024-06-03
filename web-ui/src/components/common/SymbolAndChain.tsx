import TradingSymbol from 'tradingSymbol'
import SymbolIcon from 'components/common/SymbolIcon'

interface Props {
  symbol: TradingSymbol
  noIcon?: boolean
}

export function SymbolAndChain({ symbol, noIcon }: Props) {
  return (
    <div className="flex text-sm font-normal">
      {!noIcon && (
        <SymbolIcon
          symbol={symbol}
          className="mr-2 inline-block size-6 align-top"
        />
      )}
      <span className="leading-none">
        {symbol.name}
        {noIcon ? (
          <> ({symbol.chainName})</>
        ) : (
          <>
            <br />
            <span className="text-xs">{symbol.chainName}</span>
          </>
        )}
      </span>
    </div>
  )
}
