import LogoOrange from 'assets/logo-orange.svg'
import React from 'react'

export default function Logo() {
  return (
    <div className="w-screen">
      <div className="mx-4 flex justify-between gap-4 pt-4">
        <img src={LogoOrange} alt="ChainRing" />
        <div className="text-right">
          <div className="font-sans font-semibold text-brightOrange">
            When every
          </div>
          <div className="font-sans text-dullOrange">millisecond counts</div>
        </div>
      </div>
    </div>
  )
}
