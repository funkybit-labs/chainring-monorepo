import { GoalsSvg } from 'components/icons'
import CRSvg from 'assets/cr.svg'
import GithubIconSvg from 'assets/github-icon.svg'
import DiscordIconSvg from 'assets/discord-icon.svg'
import MediumIconSvg from 'assets/medium-icon.svg'
import LinkedInIconSvg from 'assets/linkedin-icon.svg'
import XIconSvg from 'assets/x-icon.svg'
import CheckmarkSvg from 'assets/checkmark.svg'
import { Button } from 'components/common/Button'
import { classNames } from 'utils'

export default function GoalsTab() {
  return (
    <>
      <div className="fixed left-0 top-0 flex h-40 w-full flex-col justify-between gap-2 px-4 pb-3 pt-4">
        <div className="flex justify-center text-white">
          <GoalsSvg className="size-[60px]" />
        </div>
        <div className="flex items-center justify-center gap-2">
          <img src={CRSvg} className="size-[24px]" />
          <div className="flex items-baseline gap-2">
            <span className="text-lg font-bold text-primary5">3x</span>
            <span className="text-lg font-bold text-white">Reward</span>
            <span className="text-darkBluishGray2">01:56:13 time left</span>
          </div>
        </div>
        <div className="flex gap-2">
          <div className="font-bold text-white">3/5</div>
          <div className="text-darkBluishGray2">Goals available</div>
        </div>
      </div>
      <div className="h-full pt-40">
        <div className="flex h-full flex-col gap-4 overflow-auto px-4">
          <GoalRow
            iconSrc={GithubIconSvg}
            description="Github Subscription"
            reward={1000}
            complete={false}
          />
          <GoalRow
            iconSrc={DiscordIconSvg}
            description="Discord Subscription"
            reward={1000}
            complete={false}
          />
          <GoalRow
            iconSrc={MediumIconSvg}
            description="Medium Subscription"
            reward={1000}
            complete={false}
          />
          <GoalRow
            iconSrc={LinkedInIconSvg}
            description="Linkedin Subscription"
            reward={1000}
            complete={true}
          />
          <GoalRow
            iconSrc={XIconSvg}
            description="X (Twitter) Subscription"
            reward={1000}
            complete={true}
          />
          <GoalRow
            iconSrc={MediumIconSvg}
            description="Medium Subscription"
            reward={1000}
            complete={false}
          />
          <GoalRow
            iconSrc={LinkedInIconSvg}
            description="Linkedin Subscription"
            reward={1000}
            complete={true}
          />
          <GoalRow
            iconSrc={XIconSvg}
            description="X (Twitter) Subscription"
            reward={1000}
            complete={true}
          />
        </div>
      </div>
    </>
  )
}

function GoalRow({
  iconSrc,
  description,
  reward,
  complete
}: {
  iconSrc: string
  description: string
  reward: number
  complete: boolean
}) {
  return (
    <div
      className={classNames(
        'bg-darkBluishGray9 rounded-lg px-4 py-2 text-white flex justify-between',
        complete && 'border-2 border-primary5'
      )}
    >
      <div className="flex items-center justify-between gap-4">
        <img src={iconSrc} className="size-[32px]" />
        <div>
          <div className="font-semibold text-white">{description}</div>
          <div className="text-sm text-darkBluishGray1">{reward} CRs</div>
        </div>
      </div>
      {complete ? (
        <div className="flex w-[65px] items-center justify-center">
          <img src={CheckmarkSvg} />
        </div>
      ) : (
        <Button
          className="my-0.5"
          caption={() => (
            <div className="px-1 font-semibold tracking-wide">GO</div>
          )}
          onClick={() => {}}
        />
      )}
    </div>
  )
}
