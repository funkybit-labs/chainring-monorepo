import React from 'react'
import CRSvg from 'assets/cr.svg'

export function PlayTheGameWidget({
  onEnterGame
}: {
  onEnterGame: () => void
}) {
  return (
    <div
      onClick={onEnterGame}
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
  )
}
