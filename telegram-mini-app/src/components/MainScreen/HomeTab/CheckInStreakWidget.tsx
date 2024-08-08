import React from 'react'
import { CheckInStreak } from 'apiClient'
import FirePng from 'assets/fire.png'
import { decimalAsInt } from 'utils/format'
import { Button } from 'components/common/Button'
import { useClose } from '@headlessui/react'

export function CheckInStreakWidget({ streak }: { streak: CheckInStreak }) {
  const daysLabel = streak.days === 1 ? 'day' : 'days'
  const ticketsLabel = streak.gameTickets === 1 ? 'ticket' : 'tickets'

  const close = useClose()

  return (
    <div className="flex flex-col items-center justify-center text-center">
      <img src={FirePng} alt="Check In Streak" className="size-32" />
      <div className="text-lg text-brightOrange">
        {streak.days} {daysLabel}
      </div>
      <div className="text-sm text-white">Check in streak</div>
      <div className="mt-8 text-xl text-brightOrange">
        +{decimalAsInt(streak.reward)} FB and {streak.gameTickets}{' '}
        {ticketsLabel}.
      </div>
      <Button className={'mt-2 p-1'} caption={() => 'Ok'} onClick={close} />
    </div>
  )
}
