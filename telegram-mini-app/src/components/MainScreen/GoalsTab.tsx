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
import { apiClient, User, UserGoal, userQueryKey } from 'apiClient'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useWebApp } from '@vkruglikov/react-telegram-web-app'
import { useState } from 'react'

export default function GoalsTab({ user }: { user: User }) {
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
          <div className="font-bold text-white">
            {user.goals.filter((g) => !g.achieved).length}/{user.goals.length}
          </div>
          <div className="text-darkBluishGray2">Goals available</div>
        </div>
      </div>
      <div className="h-full pt-40">
        <div className="flex h-full flex-col gap-4 overflow-auto px-4">
          {user.goals.map((goal) => {
            return <GoalRow key={goal.id} goal={goal} />
          })}
        </div>
      </div>
    </>
  )
}

function GoalRow({ goal }: { goal: UserGoal }) {
  const queryClient = useQueryClient()
  const telegramWebApp = useWebApp()
  const [status, setStatus] = useState<
    'notAchieved' | 'linkOpened' | 'rewardReady' | 'achieved'
  >(goal.achieved ? 'achieved' : 'notAchieved')

  const claimRewardMutation = useMutation({
    mutationFn: async () => apiClient.claimReward({ goalId: goal.id }),
    onSuccess: () => {
      setStatus('achieved')
      return queryClient.invalidateQueries({ queryKey: userQueryKey })
    },
    onError: () => {
      alert('Something went wrong')
    }
  })

  const [iconSrc, description, url] = (() => {
    switch (goal.id) {
      case 'GithubSubscription':
        return [
          GithubIconSvg,
          'Github Subscription',
          'https://github.com/Chainring-Inc'
        ]
      case 'DiscordSubscription':
        return [DiscordIconSvg, 'Discord Subscription', 'https://discord.com/']
      case 'MediumSubscription':
        return [MediumIconSvg, 'Medium Subscription', 'https://medium.com/']
      case 'LinkedinSubscription':
        return [
          LinkedInIconSvg,
          'LinkedIn Subscription',
          'https://www.linkedin.com/company/chainring-inc/'
        ]
      case 'XSubscription':
        return [XIconSvg, 'X (Twitter) Subscription', 'https://x.com']
    }
  })()

  return (
    <div
      className={classNames(
        'bg-darkBluishGray9 rounded-lg px-4 py-2 text-white flex justify-between',
        status == 'achieved' && 'border-2 border-primary5'
      )}
    >
      <div className="flex items-center justify-between gap-4">
        <img src={iconSrc} className="size-[32px]" />
        <div>
          <div className="font-semibold text-white">{description}</div>
          <div className="text-sm text-darkBluishGray1">
            {goal.reward.toString()} CRs
          </div>
        </div>
      </div>
      {status == 'achieved' ? (
        <div className="flex w-[65px] items-center justify-center">
          <img src={CheckmarkSvg} />
        </div>
      ) : (
        <Button
          disabled={status === 'linkOpened' || claimRewardMutation.isPending}
          className="my-0.5"
          caption={() => (
            <div className="whitespace-nowrap px-1 font-semibold">
              {status == 'rewardReady' ? 'Claim' : 'GO'}
            </div>
          )}
          onClick={() => {
            if (status == 'notAchieved') {
              telegramWebApp.openLink(url)
              setStatus('linkOpened')
              window.setTimeout(() => {
                setStatus('rewardReady')
              }, 4000)
            } else if (status == 'rewardReady') {
              claimRewardMutation.mutate()
            }
          }}
        />
      )}
    </div>
  )
}
