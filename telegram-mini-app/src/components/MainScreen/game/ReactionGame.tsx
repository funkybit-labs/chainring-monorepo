import Decimal from 'decimal.js'
import React, { useEffect, useRef, useState } from 'react'
import { ApiErrorsSchema } from 'apiClient'
import { BrowserView, isBrowser, MobileView } from 'react-device-detect'
import GameLogoSvg from 'assets/game-logo.svg'
import ArrowDownSvg from 'assets/arrow-down.svg'
import { Button } from 'components/common/Button'
import { classNames } from 'utils'

export type ReactionGameResult = {
  percentile: number
  reward: Decimal
}

type GameState = 'idle' | 'waiting' | 'ready' | 'finished' | 'early'

export function ReactionGame({
  tickets,
  onReactionTimeMeasured,
  onClose
}: {
  tickets: number
  onReactionTimeMeasured: (
    reactionTimeMs: number
  ) => Promise<ReactionGameResult>
  onClose: () => void
}) {
  const [gameState, setGameState] = useState<GameState>('idle')
  const [elapsedTime, setElapsedTime] = useState(0)
  const [gameResult, setGameResult] = useState<ReactionGameResult>()
  const [apiError, setApiError] = useState<string>()

  const startTimeRef = useRef<number | null>(null)
  const timeoutRef = useRef<NodeJS.Timeout | null>(null)
  const circleRef = useRef<HTMLDivElement | null>(null)

  const startGame = () => {
    setGameState('waiting')
    setElapsedTime(0)
    setGameResult(undefined)

    // random delay between 3s and 5s
    const randomDelay = Math.random() * 3000 + 2000
    timeoutRef.current = setTimeout(() => {
      if (circleRef.current) {
        circleRef.current.classList.add('bg-gameBlue')
      }
      setGameState('ready')
      startTimeRef.current = Date.now()
    }, randomDelay)
  }

  const handleClick = async () => {
    if (gameState === 'waiting') {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
      }
      setGameState('early')
      // Wait 2 seconds and restart the game
      setTimeout(() => {
        startGame()
      }, 2000)
    } else if (gameState === 'ready' && startTimeRef.current) {
      const timeElapsed = Date.now() - startTimeRef.current
      setElapsedTime(timeElapsed)
      setGameState('finished')

      onReactionTimeMeasured(timeElapsed)
        .then((result) => {
          setGameResult(result)
        })
        .catch((error) => {
          let errorMessage = 'An unexpected error occurred'
          if (
            error.response &&
            ApiErrorsSchema.safeParse(error.response.data).success
          ) {
            const parsedErrors = ApiErrorsSchema.parse(error.response.data)
            errorMessage = parsedErrors.errors
              .map((err) => err.displayMessage)
              .join(', ')
          }
          setApiError(errorMessage)
        })
    }
  }

  const percentilesMessage = (percentile: number) => {
    if (percentile >= 75) {
      return `Congratulations! You are faster than ${percentile}% of ChainRin users!`
    } else if (percentile >= 25) {
      return `Not bad! You are faster than ${percentile}% of ChainRing users!`
    } else {
      return `Keep trying! You are faster than ${percentile}% of ChainRing users! `
    }
  }

  useEffect(() => {
    if (circleRef.current) {
      circleRef.current.classList.remove(
        'animate-gameOrangeBlink',
        'bg-gameBlue'
      )

      if (gameState === 'waiting') {
        circleRef.current.classList.add('animate-gameOrangeBlink')
      } else if (gameState === 'ready' || gameState === 'finished') {
        circleRef.current.classList.add('bg-gameBlue')
      }
    }
  }, [gameState])

  useEffect(() => {
    const handleSpacebar = (event: KeyboardEvent) => {
      if (event.code === 'Space') {
        handleClick()
      }
    }

    if (isBrowser && (gameState === 'waiting' || gameState === 'ready')) {
      window.addEventListener('keydown', handleSpacebar)
    } else {
      window.removeEventListener('keydown', handleSpacebar)
    }

    return () => {
      window.removeEventListener('keydown', handleSpacebar)
    }
  })

  return (
    <div className="fixed inset-0 z-50 flex cursor-default select-none flex-col items-center justify-center bg-darkBluishGray10 text-white">
      <div className="flex w-full flex-col items-center justify-center text-white">
        {(gameState === 'idle' ||
          gameState === 'waiting' ||
          gameState === 'early') && (
          <div className="absolute left-0 top-4 w-full px-10 text-center text-xl">
            Are you faster than the blink of an eye?
          </div>
        )}
        {gameState === 'finished' && gameResult && (
          <div className="absolute left-0 top-4 flex w-full flex-col px-10 text-center text-xl">
            <span>{percentilesMessage(gameResult.percentile)}</span>
            <span className="mt-4">
              +{gameResult.reward.toFixed(0)} CR Points
            </span>
          </div>
        )}
        {gameState === 'finished' && apiError && (
          <div className="absolute left-0 top-4 flex w-full flex-col px-10 text-center text-xl text-gameRed">
            <span>{apiError}</span>
          </div>
        )}
        <button
          onClick={onClose}
          className="absolute right-4 top-2 font-light text-white"
        >
          X
        </button>
      </div>

      <div className="flex items-center justify-center text-white">
        <div className="relative">
          <img src={GameLogoSvg} className="size-[213px]" alt="Game" />
          <div
            ref={circleRef}
            onClick={handleClick}
            className="absolute inset-0 m-auto flex size-[158px] cursor-pointer items-center justify-center rounded-full"
          >
            {gameState === 'finished' && (
              <span
                className="text-2xl font-extrabold text-gameBlack"
                style={{
                  WebkitTextStroke: '0.75px white'
                }}
              >
                {elapsedTime} ms
              </span>
            )}
            {gameState === 'early' && (
              <span className="text-xl text-gameRed">Too early!</span>
            )}
          </div>
        </div>
      </div>

      <div className="flex w-full items-center justify-center text-white">
        {gameState === 'finished' && (
          <div>
            <div className="absolute bottom-36 left-0 flex w-full flex-col px-10 text-center text-xl">
              You’re still much slower than ChainRing though...
            </div>

            <div className="absolute bottom-9 left-0 flex w-full">
              <Scale measuredTime={elapsedTime} />
            </div>
          </div>
        )}

        <div className="absolute bottom-6 w-full items-center justify-center px-6 text-center text-xl">
          {(gameState === 'idle' || gameState === 'finished') && (
            <Button
              caption={() => {
                if (apiError || tickets <= 0) {
                  return 'No tickets remaining'
                } else if (gameState === 'idle') {
                  return 'Start'
                } else {
                  return `Try again (${tickets} tickets left)`
                }
              }}
              onClick={() => {
                if (!apiError && tickets > 0) startGame()
                else onClose()
              }}
              className={classNames(
                'mt-4 w-full border-2 !bg-darkBluishGray10 p-2',
                tickets <= 0 ? '!border-gameDisabled' : '!border-primary5'
              )}
            />
          )}
          {(gameState === 'waiting' || gameState === 'early') && (
            <div>
              <BrowserView>
                Press the spacebar when the ring turns{' '}
                <span className="text-gameBlue">blue!</span>
              </BrowserView>
              <MobileView>
                Tap on the ring when it turns{' '}
                <span className="text-gameBlue">blue!</span>
              </MobileView>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

const ticks = [
  { description: 'A deep breath', label: '2.5s', timeMs: 2500 },
  {
    description: 'Light from the earth to the moon',
    label: '1.3s',
    timeMs: 1300
  },
  { description: 'Heartbeat', label: '500ms', timeMs: 500 },
  { description: 'Blink of an eye', label: '150ms', timeMs: 150 },
  { description: 'Lizard darts its tongue', label: '25ms', timeMs: 25 },
  { description: 'Light from LA to NYC', label: '10ms', timeMs: 10 },
  { description: 'ChainRing', label: '1ms', timeMs: 1 }
]

const Scale = ({ measuredTime }: { measuredTime: number }) => {
  const [currentTick, setCurrentTick] = useState(-1)
  const [finished, setFinished] = useState(false)

  useEffect(() => {
    if (currentTick < ticks.length) {
      const timer = setTimeout(() => {
        setCurrentTick(currentTick + 1)
      }, 2000)
      return () => clearTimeout(timer)
    } else {
      const finalTimer = setTimeout(() => {
        setFinished(true)
      }, 2000)
      return () => clearTimeout(finalTimer)
    }
  }, [currentTick])

  const getTickPosition = (timeMs: number) => {
    const minLog = Math.log10(1)
    const maxLog = Math.log10(Math.max(...ticks.map((tick) => tick.timeMs)))
    const logTime = Math.log10(timeMs)

    // invert scale and position results from 10% to 90%
    return 10 + 80 * (1 - (logTime - minLog) / (maxLog - minLog))
  }

  return (
    <div className="relative h-24 w-full overflow-hidden">
      {ticks.map((tick, index) => (
        <div
          key={index}
          className="absolute transition-all ease-out duration-[1.5s]"
          style={{
            left: `calc(${
              index <= currentTick ? getTickPosition(tick.timeMs) : 100
            }%)`
          }}
        >
          <div className="relative text-center text-xxxs">
            <span
              className={classNames(
                'absolute top-4 transform -translate-x-1/2 transition-opacity 0.2s',
                index <= currentTick ? 'opacity-100' : 'opacity-0'
              )}
            >
              {tick.label}
            </span>
            <div
              className={classNames(
                'absolute top-7 transform -translate-x-1/2 w-[1px] h-1.5 bg-white transition-opacity 0.2s',
                index <= currentTick ? 'opacity-100' : 'opacity-0'
              )}
            ></div>
          </div>
          <div
            className={classNames(
              'absolute top-0 transform -translate-x-1/2 text-center text-xxxs whitespace-nowrap',
              index < currentTick
                ? 'opacity-0'
                : index === currentTick
                  ? 'opacity-100'
                  : 'opacity-0'
            )}
            style={{
              transition:
                index === currentTick ? 'opacity 0.2s' : 'opacity 1.5s'
            }}
          >
            {tick.description}
          </div>
          {index === 0 && (
            <div className="absolute top-8 h-0.5 w-[2000px] bg-white"></div>
          )}
        </div>
      ))}
      <div
        className={classNames(
          'absolute text-center transition-opacity 0.2s',
          finished ? 'opacity-100' : 'opacity-0'
        )}
        style={{ left: `calc(${getTickPosition(measuredTime)}%)` }}
      >
        <div className="absolute left-1/2 top-1 w-2 -translate-x-1/2">
          <img src={ArrowDownSvg} className="size-[30px]" alt="You" />
        </div>
        <span className="absolute top-8 -translate-x-1/2 text-xs text-gameOrange">
          You
        </span>
      </div>
    </div>
  )
}
