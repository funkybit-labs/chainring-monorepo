import { JSX } from 'react'
import { Tooltip } from 'components/common/Tooltip'

export function TnChallengeDepositsLimitedTooltip({
  children
}: {
  children: JSX.Element
}) {
  return (
    <Tooltip
      message={
        'Deposits are limited during the Testnet Challenge to previously withdrawn assets'
      }
      style={'normal'}
      classNames={'z-50 -top-6 -right-32 w-40'}
    >
      {children}
    </Tooltip>
  )
}
