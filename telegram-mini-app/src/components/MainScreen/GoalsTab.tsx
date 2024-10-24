import GithubIconSvg from 'assets/github-icon.svg'
import DiscordIconSvg from 'assets/discord-icon.svg'
import LinkedInIconSvg from 'assets/linkedin-icon.svg'
import XIconSvg from 'assets/x-icon.svg'
import { Button } from 'components/common/Button'
import { classNames } from 'utils'
import { apiClient, GoalId, User, UserGoal, userQueryKey } from 'apiClient'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useWebApp } from '@vkruglikov/react-telegram-web-app'
import React, { useMemo, useState } from 'react'
import { HeadSub, InfoPanel } from 'components/common/InfoPanel'
import KeyPng from 'assets/key.png'
import { ProgressBar } from 'components/common/ProgressBar'
import Decimal from 'decimal.js'
import { Modal } from 'components/common/Modal'
import Logo from 'components/common/Logo'
import { useClose } from '@headlessui/react'

function GoalAchieved({ goalAchieved }: { goalAchieved?: UserGoal }) {
  const { iconSrc: goalAchievedIconSrc, description: goalAchievedDescription } =
    useMemo(() => {
      if (goalAchieved) {
        return goalInfo(goalAchieved.id)
      } else {
        return { iconSrc: '', description: '', url: '', verified: false }
      }
    }, [goalAchieved])

  const close = useClose()

  return (
    <div className="flex flex-col items-center gap-2">
      <img
        className="size-32"
        src={goalAchievedIconSrc}
        alt={goalAchievedDescription}
      />
      <div className="text-2xl font-normal text-brightOrange">
        +{goalAchieved?.reward?.toString() ?? ''} FB
      </div>
      <div className="text-center text-lg leading-6 text-white">
        Thank you for following us on {goalAchievedDescription}. Complete more
        goals for more FBs.
      </div>
      <Button className={'mt-2 p-1'} caption={() => 'Ok'} onClick={close} />
    </div>
  )
}

export default function GoalsTab({ user }: { user: User }) {
  const goalCount = user.goals.length
  const achievedGoals = user.goals.filter((g) => g.achieved)
  const availableReward = user.goals
    .map((g) => g.reward)
    .reduce((a, v) => a.plus(v), new Decimal(0))
  const earnedReward = achievedGoals
    .map((g) => g.reward)
    .reduce((a, v) => a.plus(v), new Decimal(0))
  const [showGoalAchievedModal, setShowGoalAchievedModal] = useState(false)
  const [goalAchieved, setGoalAchieved] = useState<UserGoal>()

  return (
    <>
      <div className="relative flex select-none flex-col">
        <Logo />
        <div className="mx-6 flex flex-col">
          <div className="mt-2 text-left font-sans text-2xl font-semibold text-white">
            Goals
          </div>

          <InfoPanel icon={KeyPng} rounded="top">
            <div className="flex flex-col">
              <span>
                GOALS ({achievedGoals.length}/{goalCount})
              </span>
              <ProgressBar
                value={new Decimal(achievedGoals.length)}
                min={new Decimal(0)}
                max={new Decimal(goalCount)}
              />
              <span className="text-2xl">
                {earnedReward.toString()}/{availableReward.toString()} FB
              </span>
            </div>
          </InfoPanel>
          <div className="flex flex-col gap-0 overflow-auto">
            {user.goals.map((goal, ix) => {
              return (
                <GoalRow
                  key={goal.id}
                  goal={goal}
                  isLast={ix === user.goals.length - 1}
                  onAchieved={() => {
                    setGoalAchieved(goal)
                    setShowGoalAchievedModal(true)
                  }}
                />
              )
            })}
          </div>
          {showGoalAchievedModal && (
            <Modal
              close={() => setShowGoalAchievedModal(false)}
              onClosed={() => {}}
              isOpen={showGoalAchievedModal}
            >
              <GoalAchieved goalAchieved={goalAchieved} />
            </Modal>
          )}
        </div>
      </div>
    </>
  )
}

function goalInfo(goalId: GoalId) {
  switch (goalId) {
    case 'GithubSubscription':
      return {
        iconSrc: GithubIconSvg,
        description: 'Github',
        url: 'https://github.com/funkybit-labs',
        verified: false
      }
    case 'DiscordSubscription':
      return {
        iconSrc: DiscordIconSvg,
        description: 'Discord',
        url: `${import.meta.env.ENV_WEB_URL}/oauth-relay?flow=discord`,
        verified: true
      }
    case 'LinkedinSubscription':
      return {
        iconSrc: LinkedInIconSvg,
        description: 'LinkedIn',
        url: 'https://www.linkedin.com/company/funkybit-inc/',
        verified: false
      }
    case 'XSubscription':
      return {
        iconSrc: XIconSvg,
        description: 'X (Twitter)',
        url: 'https://x.com/funkybit_fun',
        verified: false
      }
  }
}

function GoalRow({
  goal,
  isLast,
  onAchieved
}: {
  goal: UserGoal
  isLast: boolean
  onAchieved: () => void
}) {
  const queryClient = useQueryClient()
  const telegramWebApp = useWebApp()
  const [status, setStatus] = useState<
    'notAchieved' | 'linkOpened' | 'rewardReady' | 'achieved'
  >(goal.achieved ? 'achieved' : 'notAchieved')

  const startVerifiedRewardFlow = useMutation({
    mutationFn: async (url: string) => {
      apiClient.oauthRelayAuthToken(undefined).then((token) => {
        telegramWebApp.openLink(url + `&token=${encodeURIComponent(token)}`)
        setStatus('linkOpened')
      })
    }
  })

  const claimRewardMutation = useMutation({
    mutationFn: async () => apiClient.claimReward({ goalId: goal.id }),
    onSuccess: () => {
      setStatus('achieved')
      onAchieved()
      return queryClient.invalidateQueries({ queryKey: userQueryKey })
    },
    onError: () => {
      alert('Something went wrong')
    }
  })

  const { iconSrc, description, url, verified } = goalInfo(goal.id)

  return (
    <InfoPanel icon={iconSrc} rounded={isLast ? 'bottom' : 'none'}>
      <>
        <HeadSub
          head={description}
          sub={`${goal.reward} FBs`}
          style={'largeSub'}
        />
        <div className="flex w-full justify-end">
          <Button
            disabled={
              status === 'linkOpened' ||
              status === 'achieved' ||
              claimRewardMutation.isPending
            }
            className={classNames(
              'my-0.5 py-2 !bg-modalBlue',
              status === 'rewardReady' && '!bg-white/50',
              status === 'achieved' &&
                '!bg-brightOrange !bg-opacity-70 !opacity-100'
            )}
            caption={() => (
              <div className="whitespace-nowrap px-1 font-medium">
                {status === 'rewardReady'
                  ? 'Claim'
                  : status === 'achieved'
                    ? 'Followed'
                    : 'Follow'}
              </div>
            )}
            onClick={() => {
              if (status == 'notAchieved') {
                if (verified) {
                  startVerifiedRewardFlow.mutate(url)
                } else {
                  telegramWebApp.openLink(url)
                  setStatus('linkOpened')
                }
                window.setTimeout(() => {
                  setStatus('rewardReady')
                }, 4000)
              } else if (status == 'rewardReady') {
                claimRewardMutation.mutate()
              }
            }}
          />
        </div>
      </>
    </InfoPanel>
  )
}
