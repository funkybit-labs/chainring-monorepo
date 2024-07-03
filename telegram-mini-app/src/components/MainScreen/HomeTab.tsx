import LogoHSvg from 'assets/logo-h.svg'
import CRSvg from 'assets/cr.svg'
import TicketSvg from 'assets/ticket.svg'
import InviteSvg from 'assets/invite.svg'
import MillisecondsSvg from 'assets/milliseconds.svg'
import { apiClient, User, userQueryKey } from 'apiClient'
import React, { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ReactionGame,
  ReactionGameResult
} from 'components/MainScreen/game/ReactionGame'
import { PlayTheGameWidget } from 'components/MainScreen/HomeTab/PlayTheGameWidget'
import { CheckInStreakWidget } from 'components/MainScreen/HomeTab/CheckInStreakWidget'
import { MilestoneReachedWidget } from 'components/MainScreen/HomeTab/MilestoneReachedWidget'
import { CRView } from 'components/common/CRView'

export default function HomeTab({ user }: { user: User }) {
  const queryClient = useQueryClient()
  const [balance, setBalance] = useState(user.balance)
  const [gameTickets, setGameTickets] = useState(user.gameTickets)
  const [isGameMode, setGameMode] = useState(false)
  const fiveMinutesInMs = 5 * 60 * 1000

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
    <div className="relative flex min-h-full select-none flex-col pb-10">
      <div>
        <div className="mx-4 flex gap-4 pt-4">
          <img src={LogoHSvg} />
          <div className="text-left text-lg font-semibold text-white">
            When every millisecond counts
          </div>
        </div>
        <div className="mr-4 mt-4">
          <img src={MillisecondsSvg} />
        </div>
      </div>
      <div className="flex grow flex-col justify-center">
        <div className="flex grow flex-col items-center justify-center">
          <div className="mt-2 text-center text-sm text-darkBluishGray2">
            Your balance
          </div>
          <div className="mx-auto mt-2 flex w-4/5 flex-col items-center justify-center rounded-lg bg-darkBluishGray9 px-5 py-4">
            <div className="flex items-center justify-center gap-4 text-xl font-bold text-white">
              <img src={CRSvg} className="size-7" alt="CR icon" />
              <CRView amount={balance} format={'full'} />
            </div>
            {user.nextMilestoneIn && (
              <div className="mt-2 flex w-full flex-col">
                <div className="text-center text-sm text-white">
                  Next milestone in <CRView amount={user.nextMilestoneIn} />!
                </div>
              </div>
            )}
          </div>
          <div className="mx-auto mt-2 flex w-4/5 items-center justify-center gap-4 rounded-lg bg-darkBluishGray9 px-5 py-4 text-xl font-bold text-white">
            <img src={TicketSvg} className="size-7" alt="Ticket icon" />
            {gameTickets} Game Tickets
          </div>
          <div className="mx-auto mt-2 flex w-4/5 items-center justify-center gap-4 rounded-lg bg-darkBluishGray9 px-5 py-4 text-xl font-bold text-white">
            <img src={InviteSvg} className="size-7" alt="Invites icon" />
            {user.invites === -1
              ? 'Unlimited Invites'
              : `${user.invites} Invite${user.invites === 1 ? '' : 's'} Left`}
          </div>
        </div>
        <div className="mx-auto my-4 flex flex-col place-content-between">
          {user.checkInStreak.grantedAt.getTime() + fiveMinutesInMs >
            new Date().getTime() && (
            <CheckInStreakWidget streak={user.checkInStreak} />
          )}
          <div className="flex w-full flex-col place-content-between">
            {user.lastMilestone &&
              user.lastMilestone.grantedAt.getTime() + fiveMinutesInMs >
                new Date().getTime() && (
                <div className="flex flex-col ">
                  <MilestoneReachedWidget milestone={user.lastMilestone} />
                </div>
              )}
          </div>
        </div>
        <div className="mb-4 flex flex-col items-center justify-center">
          {gameTickets > 0 && <PlayTheGameWidget onEnterGame={enterGame} />}
        </div>
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
