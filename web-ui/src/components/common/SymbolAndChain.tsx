import TradingSymbol from 'tradingSymbol'
import SymbolIcon from 'components/common/SymbolIcon'
import { classNames } from 'utils'

interface Props {
  symbol: TradingSymbol
  noIcon?: boolean
  className?: string
  iconSize?: number
}

export function SymbolAndChain({ symbol, noIcon, className, iconSize }: Props) {
  return (
    <div
      className={classNames(
        'flex font-normal',
        className ? className : 'text-sm'
      )}
    >
      {!noIcon && (
        <SymbolIcon
          symbol={symbol}
          className={classNames(
            'mr-2 inline-block align-top',
            iconSize ? `size-${iconSize}` : 'size-6'
          )}
        />
      )}
      <span className="leading-none">
        {symbol.displayName()}
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
