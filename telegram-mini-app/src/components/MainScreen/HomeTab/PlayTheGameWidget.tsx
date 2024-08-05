import React from 'react'
import CoinSvg from 'assets/coin.svg'

export function PlayTheGameWidget({
  onEnterGame
}: {
  onEnterGame: () => void
}) {
  return (
    <div onClick={onEnterGame} className="mx-6">
      <div className="mx-auto mt-2 flex flex-col items-stretch rounded-3xl bg-darkBlue px-6 py-4">
        <div className="flex flex-row">
          <img src={CoinSvg} className="inline h-16 w-auto" alt="icon" />
          <div className="ml-4 text-2xl text-white">
            Are you faster than
            <br />
            the blink of an eye?
          </div>
        </div>
        <button className="mt-4 h-10 rounded-3xl bg-brightOrange/70 px-6 font-semibold text-white">
          Play game
        </button>
      </div>
    </div>
  )
}
