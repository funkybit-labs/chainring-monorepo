import LogoOrange from 'assets/funkybit-orange-logo.png'
import LogoWords from 'assets/logo-words.png'
import React from 'react'

export default function Logo() {
  return (
    <div className="w-screen">
      <div className="mx-4 flex items-center justify-between pb-4">
        <div className="grow">
          <img src={LogoOrange} alt="funkybit" className="inline size-10" />
          <img
            src={LogoWords}
            alt="funkybit"
            className="ml-2 mt-1.5 inline h-6"
          />
        </div>
        <div className="text-right text-white">
          A Revolution in
          <br />
          Bitcoin-Native Trading
        </div>
      </div>
    </div>
  )
}
