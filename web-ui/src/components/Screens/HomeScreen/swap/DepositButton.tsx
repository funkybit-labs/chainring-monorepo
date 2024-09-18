import React from 'react'
import Deposit from 'assets/Deposit.svg'
import { TnChallengeDepositsLimitedTooltip } from 'components/common/TnChallengeDepositsLimitedTooltip'

export function DepositButton({
  testnetChallengeDepositLimit,
  onClick
}: {
  testnetChallengeDepositLimit?: bigint
  onClick: () => void
}) {
  const button = (
    <button
      disabled={testnetChallengeDepositLimit === 0n}
      className="rounded bg-darkBluishGray6 px-2 py-1 text-darkBluishGray2 hover:bg-blue5"
      onClick={onClick}
    >
      <span className="hidden narrow:inline">Deposit</span>
      <img className="hidden max-narrow:inline" src={Deposit} alt={'Deposit'} />
    </button>
  )
  return testnetChallengeDepositLimit === 0n ? (
    <TnChallengeDepositsLimitedTooltip>
      {button}
    </TnChallengeDepositsLimitedTooltip>
  ) : (
    button
  )
}
