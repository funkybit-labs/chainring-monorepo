import Decimal from 'decimal.js'
import React, { Fragment, useEffect, useRef, useState } from 'react'
import { ApiErrorsSchema } from 'apiClient'
import { BrowserView, isBrowser, MobileView } from 'react-device-detect'
import StopwatchSvg from 'assets/stopwatch.svg'
import FlagPng from 'assets/flag.png'
import { Button } from 'components/common/Button'
import { classNames } from 'utils'
import XSvg from 'assets/X.svg'
import LogoSvg from 'assets/logo-orange-no-words.svg'
import GameBasePng from 'assets/game-base.png'
import TrophyPng from 'assets/trophy.png'
import { InfoPanel } from 'components/common/InfoPanel'
import { decimalAsInt } from 'utils/format'

export type ReactionGameResult = {
  percentile: number
  reward: Decimal
}

type GameState = 'idle' | 'waiting' | 'ready' | 'finished' | 'early' | 'late'

const MAX_REACTION_TIME = 5000

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
  const readyTimeoutRef = useRef<NodeJS.Timeout | null>(null)
  const maxTimeTimeoutRef = useRef<NodeJS.Timeout | null>(null)
  const circleRef = useRef<HTMLDivElement | null>(null)

  const startGame = () => {
    setGameState('waiting')
    setElapsedTime(0)
    setGameResult(undefined)

    // random delay between 3s and 5s
    const randomDelay = Math.random() * 3000 + 2000
    readyTimeoutRef.current = setTimeout(() => {
      if (circleRef.current) {
        circleRef.current.classList.add('bg-gradient-to-b')
        circleRef.current.classList.add('from-gameBlueStart/50')
        circleRef.current.classList.add('to-gameBlueStop')
      }
      setGameState('ready')
      startTimeRef.current = Date.now()

      // maximum reaction time is 5 seconds
      maxTimeTimeoutRef.current = setTimeout(() => {
        setGameState((prevState) => {
          if (prevState === 'ready') {
            setTimeout(() => {
              startGame()
            }, 3000)

            return 'late'
          }
          return prevState
        })
      }, MAX_REACTION_TIME)
    }, randomDelay)
  }

  const handleTimeMeasure = async () => {
    if (gameState === 'waiting') {
      if (readyTimeoutRef.current) {
        clearTimeout(readyTimeoutRef.current)
      }
      setGameState('early')
      // Wait 1 second and restart the game
      setTimeout(() => {
        startGame()
      }, 1000)
    } else if (gameState === 'ready' && startTimeRef.current) {
      if (maxTimeTimeoutRef.current) {
        clearTimeout(maxTimeTimeoutRef.current)
      }
      const timeElapsed = Math.min(
        Date.now() - startTimeRef.current,
        MAX_REACTION_TIME
      )
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

  const percentilesLeadin = (percentile: number) => {
    if (percentile >= 75) {
      return 'Congratulations!'
    } else if (percentile >= 25) {
      return 'Not bad!'
    } else {
      return 'Keep trying!'
    }
  }
  const percentilesMessage = (percentile: number) => {
    if (percentile >= 75) {
      return `You are faster than ${percentile}% of ChainRin users!`
    } else if (percentile >= 25) {
      return `You are faster than ${percentile}% of ChainRing users!`
    } else {
      return `You are faster than ${percentile}% of ChainRing users! `
    }
  }

  useEffect(() => {
    if (circleRef.current) {
      circleRef.current.classList.remove(
        'animate-gameOrangeBlink',
        'bg-gradient-to-b',
        'from-gameBlueStart/50',
        'to-gameBlueStop'
      )

      if (gameState === 'waiting') {
        circleRef.current.classList.add('animate-gameOrangeBlink')
      } else if (gameState === 'ready' || gameState === 'finished') {
        circleRef.current.classList.add('bg-gradient-to-b')
        circleRef.current.classList.add('from-gameBlueStart/50')
        circleRef.current.classList.add('to-gameBlueStop')
      }
    }
  }, [gameState])

  useEffect(() => {
    const handleSpacebar = (event: KeyboardEvent) => {
      if (event.code === 'Space') {
        handleTimeMeasure()
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
    <div className="fixed inset-0 z-50 flex cursor-default select-none flex-col items-center justify-center bg-mediumBlue text-white">
      <div className="flex w-full flex-col items-center justify-center text-white">
        {(gameState === 'idle' ||
          gameState === 'waiting' ||
          gameState === 'early' ||
          gameState === 'late') && (
          <div className="absolute left-0 top-4 flex w-full flex-row px-10 text-center font-sans text-2xl">
            <img src={LogoSvg} alt="ChainRing" />
            <div className="ml-4 text-start">
              Are you faster than
              <br />
              the blink of an eye?
            </div>
          </div>
        )}
        {gameState === 'finished' && gameResult && (
          <div className="absolute left-0 top-4 flex w-full flex-col px-10 text-center text-xl">
            <InfoPanel icon={TrophyPng} rounded={'both'}>
              <div className={'flex flex-col text-left font-sans'}>
                <div className={'text-2xl text-white'}>
                  +{decimalAsInt(gameResult.reward)} CR POINTS
                </div>
                <div className={'text-sm'}>
                  <span className="text-brightOrange">
                    {percentilesLeadin(gameResult.percentile)}
                  </span>{' '}
                  {percentilesMessage(gameResult.percentile)}
                </div>
              </div>
            </InfoPanel>
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
          <img src={XSvg} alt="close" />
        </button>
      </div>

      <div className="flex items-center justify-center text-white">
        <div className="relative">
          <img src={GameBasePng} className="size-[231px]" alt="Game" />
          <div
            ref={circleRef}
            onMouseDown={handleTimeMeasure}
            onTouchStart={handleTimeMeasure}
            className="absolute inset-0 -right-0.5 -top-1.5 m-auto flex size-[188px] cursor-pointer items-center justify-center rounded-full bg-gradient-to-b from-gameBlueStart/50 to-gameBlueStop"
          >
            {gameState === 'finished' && (
              <span
                className="font-sans text-3xl font-semibold text-white"
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
            {gameState === 'late' && (
              <span className="text-xl text-gameRed">Try again!</span>
            )}
          </div>
        </div>
      </div>

      <div className="flex w-full items-center justify-center text-white">
        {gameState === 'finished' && (
          <div>
            <div className="absolute bottom-16 left-0 flex w-full">
              <Scale measuredTime={elapsedTime} />
            </div>
          </div>
        )}

        <div className="absolute bottom-12 w-full items-center justify-center px-6 text-center text-xl">
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
              className={classNames('my-4 w-full p-2 !bg-brightOrange/70')}
              disabled={tickets <= 0}
            />
          )}
          {(gameState === 'waiting' ||
            gameState === 'early' ||
            gameState === 'late') && (
            <div className={'flex flex-row align-middle'}>
              <img src={StopwatchSvg} className="size-16" />
              <div className={'ml-4 text-left font-sans text-xl'}>
                <BrowserView>
                  Press the spacebar when the ring turns{' '}
                  <span className="text-gameBlue">blue</span>
                </BrowserView>
                <MobileView>
                  Tap on the ring when it turns{' '}
                  <span className="text-gameBlue">blue</span>
                </MobileView>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

const regularTicks = [
  {
    description: 'A deep breath',
    label: '2.5s',
    timeMs: 2500,
    isYouTick: false,
    isChainRingTick: false
  },
  {
    description: 'Light from the earth to the moon',
    label: '1.3s',
    timeMs: 1300,
    isYouTick: false,
    isChainRingTick: false
  },
  { description: 'Heartbeat', label: '500ms', timeMs: 500, isYouTick: false },
  {
    description: 'Blink of an eye',
    label: '150ms',
    timeMs: 150,
    isYouTick: false,
    isChainRingTick: false
  },
  {
    description: 'Lizard darts its tongue',
    label: '25ms',
    timeMs: 25,
    isYouTick: false,
    isChainRingTick: false
  },
  {
    description: 'Light from LA to NYC',
    label: '10ms',
    timeMs: 10,
    isYouTick: false,
    isChainRingTick: false
  },
  {
    description: 'ChainRing',
    label: '1ms',
    timeMs: 1,
    isYouTick: false,
    isChainRingTick: true
  }
]

const extraTick = [
  {
    description: 'Stop a car from 175km/hr',
    label: '5',
    timeMs: 5000,
    isYouTick: false,
    isChainRingTick: false
  }
]

const Scale = ({ measuredTime }: { measuredTime: number }) => {
  const [currentTick, setCurrentTick] = useState(-1)

  let ticks = [
    ...regularTicks,
    {
      description: '',
      label: 'You',
      timeMs: measuredTime,
      isYouTick: true,
      isChainRingTick: false
    }
  ].sort((a, b) => b.timeMs - a.timeMs)

  if (ticks[0].isYouTick) {
    ticks = [...extraTick, ...ticks]
  }

  useEffect(() => {
    if (currentTick < ticks.length) {
      const timer = setTimeout(
        () => {
          setCurrentTick(currentTick + 1)
        },
        currentTick === -1 ? 500 : 1300
      )
      return () => clearTimeout(timer)
    }
  }, [currentTick, ticks])

  const getTickPosition = (timeMs: number) => {
    const minLog = Math.log10(1)
    const maxLog = Math.log10(Math.max(...ticks.map((tick) => tick.timeMs)))
    const logTime = Math.log10(timeMs)

    // invert scale and position results from 10% to 90%
    return 10 + 80 * (1 - (logTime - minLog) / (maxLog - minLog))
  }

  return (
    <div className="relative min-h-24 w-full overflow-visible">
      {ticks.map((tick, index) => (
        <div
          key={index}
          className="absolute transition-all duration-[1.2s] ease-out"
          style={{
            left: `calc(${
              index <= currentTick ? getTickPosition(tick.timeMs) : 100
            }%)`
          }}
        >
          {tick.isYouTick && (
            <div
              className={classNames(
                'absolute text-center transition-opacity 0.2s',
                index <= currentTick ? 'opacity-100' : 'opacity-0'
              )}
              style={{
                left: `calc(${getTickPosition(measuredTime)}%)`
              }}
            >
              <div className="absolute -top-6 left-6 size-16 -translate-x-1/2">
                <img src={FlagPng} alt="You" />
              </div>
              <div className="absolute -top-10 left-6 -translate-x-1/2 text-xs text-gameOrange">
                You
              </div>
            </div>
          )}
          {!tick.isYouTick && (
            <Fragment>
              <div className="relative text-center text-xxs">
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
                  'absolute top-0 transform -translate-x-1/2 text-center text-xxs whitespace-nowrap',
                  index < currentTick && !tick.isChainRingTick
                    ? 'opacity-0'
                    : index === currentTick ||
                        (index < currentTick && tick.isChainRingTick)
                      ? 'opacity-100'
                      : 'opacity-0'
                )}
                style={{
                  transition:
                    index === currentTick ? 'opacity 0.2s' : 'opacity 1.2s'
                }}
              >
                {tick.description}
              </div>
            </Fragment>
          )}
          {index === 0 && (
            <div className="absolute top-8 h-0.5 w-[2000px] bg-white"></div>
          )}
        </div>
      ))}
    </div>
  )
}
