import React from 'react'
import { LastMilestone } from 'apiClient'
import FlagPng from 'assets/flag.png'
import GiftPng from 'assets/gift.png'
import { InfoPanel } from 'components/common/InfoPanel'
import { classNames } from 'utils'
import { Button } from 'components/common/Button'
import { useClose } from '@headlessui/react'

export function MilestoneReachedWidget({
  milestone
}: {
  milestone: LastMilestone
}) {
  const close = useClose()
  return (
    <div className="flex flex-col items-center justify-center text-center">
      <img src={FlagPng} alt="Milestone Reached" className="size-32" />
      <div className="text-lg text-brightOrange">New milestone reached!!</div>
      <InfoPanel icon={GiftPng} rounded={'both'}>
        <div className="flex flex-col items-start text-start">
          <div className={classNames('whitespace-nowrap text-xl text-white')}>
            {milestone.invites === -1
              ? 'UNLIMITED INVITES'
              : `+${milestone.invites} INVITE${
                  milestone.invites === 1 ? '' : 'S'
                }`}
          </div>
          <div
            className={classNames(
              'whitespace-nowrap text-brightOrange text-sm'
            )}
          >
            Congratulations!
          </div>
          <div className={classNames('text-white text-sm')}>
            You&apos;ve earned{' '}
            {milestone.invites === -1
              ? ' unlimited invites '
              : ` ${milestone.invites} invite${
                  milestone.invites === 1 ? ' ' : 's '
                }`}
            for reaching new milestone.
          </div>
        </div>
      </InfoPanel>
      <Button className={'mt-2 p-1'} caption={() => 'Ok'} onClick={close} />
    </div>
  )
}
