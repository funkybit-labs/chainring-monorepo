import React from 'react'
import { CheckInStreak } from 'apiClient'
import { CRView } from 'components/common/CRView'

export function CheckInStreakWidget({ streak }: { streak: CheckInStreak }) {
  const daysLabel = streak.days === 1 ? 'day' : 'days'
  const ticketsLabel = streak.gameTickets === 1 ? 'Game Ticket' : 'Game Tickets'

  return (
    <div className="flex flex-col items-center justify-center px-5 text-center text-lg font-semibold text-white">
      <div className="whitespace-nowrap">
        <span className="text-2xl">🔥</span>Check-in streak:
      </div>
      <div>
        {streak.days} {daysLabel}
      </div>
      <div>
        +<CRView amount={streak.reward} />
      </div>
      <div>
        +{streak.gameTickets} {ticketsLabel}
      </div>
    </div>
  )
}
