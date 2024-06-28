import LogoHSvg from 'assets/logo-h.svg'
import CRSvg from 'assets/cr.svg'
import TicketSvg from 'assets/ticket.svg'
import MillisecondsSvg from 'assets/milliseconds.svg'
import { apiClient, User, userQueryKey } from 'apiClient'
import React, { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ReactionGame,
  ReactionGameResult
} from 'components/MainScreen/game/ReactionGame'

export default function HomeTab({ user }: { user: User }) {
  const queryClient = useQueryClient()
  const [balance, setBalance] = useState(user.balance)
  const [gameTickets, setGameTickets] = useState(user.gameTickets)
  const [isGameMode, setGameMode] = useState(false)

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
    <div className="relative flex min-h-full flex-col">
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
          <div className="mx-auto mt-4 flex w-4/5 items-center justify-center gap-4 rounded-lg bg-darkBluishGray9 px-5 py-4 text-2xl font-bold text-white">
            <img src={CRSvg} className="size-10" alt="CR icon" />
            {balance.toString()} CR
          </div>
          <div className="mx-auto mt-4 flex w-4/5 items-center justify-center gap-4 rounded-lg bg-darkBluishGray9 px-5 py-4 text-2xl font-bold text-white">
            <img src={TicketSvg} className="size-10" alt="Ticket icon" />
            {gameTickets} Game Tickets
          </div>
        </div>
        <div className="mb-4 flex flex-col items-center justify-center">
          <div className="mt-2 text-center text-white">
            Next milestone in 25,975 CR!
          </div>
          {gameTickets > 0 && (
            <div
              onClick={enterGame}
              className="relative mx-6 mt-6 flex cursor-pointer items-center justify-center overflow-hidden rounded-lg border-2 border-primary4 bg-darkBluishGray8 px-5 py-4 text-center text-xl font-semibold text-primary4"
            >
              <img
                src={CRSvg}
                className="absolute w-10 rotate-[-13deg]"
                style={{ top: '65%', left: '-2%', width: '31px' }}
                alt="icon"
              />
              <img
                src={CRSvg}
                className="absolute w-10 rotate-[-18deg]"
                style={{ top: '-5%', left: '8%', width: '20px' }}
                alt="icon"
              />
              <img
                src={CRSvg}
                className="absolute w-10 rotate-12"
                style={{ top: '2%', left: '20%', width: '22px' }}
                alt="icon"
              />
              <img
                src={CRSvg}
                className="absolute w-10 -rotate-6"
                style={{ top: '8%', left: '50%', width: '11px' }}
                alt="icon"
              />
              <img
                src={CRSvg}
                className="absolute w-10 rotate-[25deg]"
                style={{ top: '-3%', left: '75%', width: '20px' }}
                alt="icon"
              />
              <img
                src={CRSvg}
                className="absolute w-10 rotate-[20deg]"
                style={{ top: '1%', left: '90%', width: '25px' }}
                alt="icon"
              />
              <img
                src={CRSvg}
                className="absolute w-10 rotate-[19deg]"
                style={{ top: '90%', left: '95%', width: '20px' }}
                alt="icon"
              />
              <span className="relative z-10">
                Are you faster than the blink of an eye? Play the game!
              </span>
            </div>
          )}
        </div>
      </div>
      {isGameMode && (
        <ReactionGame
          tickets={gameTickets}
          onReactionTimeMeasured={useGameTicket}
          onClose={exitGame}
        />
      )}
    </div>
  )
}
