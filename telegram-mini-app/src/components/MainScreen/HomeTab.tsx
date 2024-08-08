import CoinSvg from 'assets/coin.svg'
import TicketSvg from 'assets/ticket.svg'
import GiftPng from 'assets/gift.png'
import MilestonePng from 'assets/milestone.png'
import { apiClient, User, userQueryKey } from 'apiClient'
import React, { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ReactionGame,
  ReactionGameResult
} from 'components/MainScreen/game/ReactionGame'
import { PlayTheGameWidget } from 'components/MainScreen/HomeTab/PlayTheGameWidget'
import { CheckInStreakWidget } from 'components/MainScreen/HomeTab/CheckInStreakWidget'
import { MilestoneReachedWidget } from 'components/MainScreen/HomeTab/MilestoneReachedWidget'
import { HeadSub, InfoPanel } from 'components/common/InfoPanel'
import { decimalAsInt } from 'utils/format'
import { ProgressBar } from 'components/common/ProgressBar'
import Decimal from 'decimal.js'
import { Modal } from 'components/common/Modal'
import { Alert } from 'components/EntryPoint'
import Logo from 'components/common/Logo'

export default function HomeTab({
  user,
  dismissedAlerts,
  dismissAlert
}: {
  user: User
  dismissedAlerts: Alert[]
  dismissAlert: (a: Alert) => void
}) {
  const queryClient = useQueryClient()
  const [balance, setBalance] = useState(user.balance)
  const [gameTickets, setGameTickets] = useState(user.gameTickets)
  const [isGameMode, setGameMode] = useState(false)
  const [showCheckinAward, setShowCheckinAward] = useState(false)
  const [showMilestoneReached, setShowMilestoneReached] = useState(false)
  const fiveMinutesInMs = 5 * 60 * 1000

  useEffect(() => {
    if (
      !isGameMode &&
      !dismissedAlerts.includes('checkin') &&
      user.checkInStreak.grantedAt.getTime() + fiveMinutesInMs >
        new Date().getTime()
    ) {
      setShowCheckinAward(true)
    }
  }, [
    user.checkInStreak.grantedAt,
    dismissedAlerts,
    fiveMinutesInMs,
    isGameMode
  ])

  useEffect(() => {
    if (
      !isGameMode &&
      !dismissedAlerts.includes('milestone') &&
      user.lastMilestone &&
      user.lastMilestone.grantedAt.getTime() + fiveMinutesInMs >
        new Date().getTime()
    ) {
      setShowMilestoneReached(true)
    }
  }, [user.lastMilestone, dismissedAlerts, fiveMinutesInMs, isGameMode])

  const enterGame = () => {
    setGameMode(true)
  }
  const exitGame = () => {
    setGameMode(false)
  }

  const reactionTimeMutation = useMutation({
    mutationFn: async (reactionTimeMs: number) =>
      apiClient.recordReactionTime({ reactionTimeMs }),
    onSuccess: () => {
      return queryClient.invalidateQueries({ queryKey: userQueryKey })
    },
    onError: () => {
      return queryClient.invalidateQueries({ queryKey: userQueryKey })
    }
  })

  async function useGameTicket(
    reactionTimeMs: number
  ): Promise<ReactionGameResult> {
    setGameTickets((n) => Math.max(0, n - 1))

    return reactionTimeMutation.mutateAsync(reactionTimeMs).then((data) => {
      setBalance(data.balance)
      return {
        percentile: data.percentile,
        reward: data.reward
      }
    })
  }

  return (
    <div className="relative flex min-h-full select-none flex-col">
      <Logo />
      <div className="flex grow flex-col justify-stretch">
        <div className="mx-6 flex grow flex-col justify-stretch">
          <div className="mt-2 text-left font-sans text-2xl font-semibold text-white">
            Your Balance
          </div>
          <InfoPanel icon={CoinSvg} rounded="top">
            <HeadSub head={decimalAsInt(balance)} sub={'FB POINTS'} />
          </InfoPanel>
          <div className="flex flex-row justify-stretch space-x-1">
            <InfoPanel
              icon={TicketSvg}
              rounded={user.nextMilestoneAt ? 'none' : 'bottom'}
            >
              <HeadSub
                style={'smallSub'}
                head={gameTickets.toString()}
                sub={gameTickets == 1 ? 'TICKET' : 'TICKETS'}
              />
            </InfoPanel>
            <InfoPanel
              icon={GiftPng}
              rounded={user.nextMilestoneAt ? 'none' : 'bottom'}
            >
              <HeadSub
                style={'smallSub'}
                head={
                  user.invites === -1 ? 'Unlimited' : user.invites.toString()
                }
                sub={user.invites === 1 ? 'INVITE' : 'INVITES'}
              />
            </InfoPanel>
          </div>
          {user.nextMilestoneAt && (
            <InfoPanel icon={MilestonePng} rounded="bottom">
              <div className="flex flex-col">
                <span>NEXT MILESTONE IN</span>
                <ProgressBar
                  value={balance}
                  min={user.lastMilestone?.points ?? new Decimal(0)}
                  max={user.nextMilestoneAt}
                />
                <span className="text-2xl">
                  {decimalAsInt(user.nextMilestoneAt.minus(balance))} FB
                </span>
              </div>
            </InfoPanel>
          )}
        </div>
        <div className="mb-4 w-full">
          {gameTickets > 0 && <PlayTheGameWidget onEnterGame={enterGame} />}
        </div>
        {showCheckinAward ? (
          <Modal
            close={() => {
              if (!isGameMode) {
                setShowCheckinAward(false)
                dismissAlert('checkin')
              }
            }}
            isOpen={showCheckinAward}
            onClosed={() => {}}
          >
            <CheckInStreakWidget streak={user.checkInStreak} />
          </Modal>
        ) : showMilestoneReached ? (
          <Modal
            close={() => {
              if (!isGameMode) {
                setShowMilestoneReached(false)
                dismissAlert('milestone')
              }
            }}
            isOpen={showMilestoneReached}
            onClosed={() => {}}
          >
            <MilestoneReachedWidget milestone={user.lastMilestone!} />
          </Modal>
        ) : null}
        {isGameMode && (
          <ReactionGame
            tickets={gameTickets}
            onReactionTimeMeasured={useGameTicket}
            onClose={exitGame}
          />
        )}
      </div>
    </div>
  )
}
