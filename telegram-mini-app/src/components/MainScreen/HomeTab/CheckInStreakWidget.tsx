import React from 'react'
import { CheckInStreak } from 'apiClient'
import { CRView } from 'components/common/CRView'

export function CheckInStreakWidget({ streak }: { streak: CheckInStreak }) {
  const daysLabel = streak.days === 1 ? 'day' : 'days'
  const ticketsLabel = streak.gameTickets === 1 ? 'Game Ticket' : 'Game Tickets'

  return (
    <div className="flex flex-col items-center justify-center text-center font-semibold text-white">
      <div className="whitespace-nowrap">
        <span className="text-2xl">🔥</span>Check-in streak: {streak.days}{' '}
        {daysLabel}
      </div>
      <div>
        +<CRView amount={streak.reward} format={'full'} />, +
        {streak.gameTickets} {ticketsLabel}
      </div>
    </div>
  )
}
