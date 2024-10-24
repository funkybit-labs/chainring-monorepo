import AvatarSvg from 'assets/avatar.svg'
import { abbreviatedWalletAddress, classNames } from 'utils'
import { Button } from 'components/common/Button'
import EditSvg from 'assets/Edit.svg'
import CameraSvg from 'assets/camera.svg'
import CelebrationSvg from 'assets/disco-ball.svg'
import BtcSvg from 'assets/btc.svg'
import LightBulbSvg from 'assets/lightbulb.svg'
import CopySvg from 'assets/copy.svg'
import DiscordSvg from 'assets/discord-icon.svg'
import XSvg from 'assets/twitter-x-icon.svg'
import React, {
  ChangeEvent,
  Fragment,
  useEffect,
  useMemo,
  useState
} from 'react'
import { useWallets, Wallets } from 'contexts/walletProvider'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  apiClient,
  ApiErrorsSchema,
  Balance,
  Card,
  Leaderboard as LB,
  LeaderboardType,
  UserLinkedAccountType
} from 'apiClient'
import { Modal } from 'components/common/Modal'
import { Tab, Widget } from 'components/Screens/Header'
import { CopyToClipboard } from 'react-copy-to-clipboard'
import { Tooltip } from 'react-tooltip'
import { accountConfigQueryKey } from 'components/Screens/HomeScreen'
import TradingSymbol from 'tradingSymbol'
import TradingSymbols from 'tradingSymbols'
import Decimal from 'decimal.js'
import { emitter } from 'emitter'

type EditMode = 'none' | 'name' | 'icon'

const rowsPerPage = 20

export const webInviteBaseUrl = import.meta.env.ENV_WEB_URL + '/invite'

export const cardsQueryKey = ['cards']

function Board({
  type,
  board: { page, lastPage, entries },
  goToPage
}: {
  type: LeaderboardType
  board: LB
  goToPage: (page: number) => void
}) {
  function PageButton({
    label,
    disabled
  }: {
    label: string
    disabled?: boolean
  }) {
    return (
      <button
        className={'disabled:text-neutralGray'}
        disabled={disabled ?? false}
        onClick={() => {
          if (label === '<<') {
            goToPage(1)
          } else if (label === '<') {
            goToPage(page - 1)
          } else if (label === '>>') {
            goToPage(lastPage)
          } else if (label === '>') {
            goToPage(page + 1)
          } else {
            goToPage(parseInt(label))
          }
        }}
      >
        {label}
      </button>
    )
  }
  return (
    <div className="flex grow flex-col pb-2">
      <div className="self-center pt-2 text-sm font-bold">
        {type === 'DailyPNL'
          ? 'Today'
          : type === 'WeeklyPNL'
            ? 'This Week'
            : 'All Time'}
      </div>
      <div className="my-2 grid auto-rows-fr grid-cols-[3fr_12fr_6fr_3fr] space-y-0.5 overflow-x-clip px-4">
        <div className="self-baseline text-xs font-bold uppercase">Rank</div>
        <div className="self-baseline text-xs font-bold uppercase">Name</div>
        <div className="self-baseline text-xs font-bold uppercase">Balance</div>
        <div className="self-baseline text-xs font-bold uppercase">PNL</div>
        {entries.flatMap((entry, index) => {
          return (
            <Fragment key={index}>
              <div className="text-sm">
                {(page - 1) * rowsPerPage + index + 1}.
              </div>
              <div className="flex flex-row place-items-center text-sm">
                {entry.iconUrl ? (
                  <img
                    src={entry.iconUrl}
                    alt={entry.label}
                    className="mx-1 size-5"
                  />
                ) : (
                  <div />
                )}
                <div>{entry.label}</div>
              </div>
              <div className="text-sm">${entry.value.toFixed(2)}</div>
              <div
                className={classNames(
                  'text-sm',
                  entry.pnl < 0
                    ? 'text-statusRed'
                    : entry.pnl > 0
                      ? 'text-statusGreen'
                      : ''
                )}
              >
                {entry.pnl > 0 ? '+' : ' '}
                {entry.pnl.toFixed(2)}%
              </div>
            </Fragment>
          )
        })}
      </div>
      <div className="space-x-4 self-center text-sm">
        <PageButton label={'<<'} disabled={page === 1} />
        <PageButton label={'<'} disabled={page === 1} />
        {page > 4 && <span>...</span>}
        {page > 3 && <PageButton label={`${page - 3}`} />}
        {page > 2 && <PageButton label={`${page - 2}`} />}
        {page > 1 && <PageButton label={`${page - 1}`} />}
        <span className="font-bold">{page}</span>
        {lastPage > page && <PageButton label={`${page + 1}`} />}
        {lastPage > page + 1 && <PageButton label={`${page + 2}`} />}
        {lastPage > page + 2 && <PageButton label={`${page + 3}`} />}
        {lastPage > page + 3 && <span>...</span>}
        <PageButton label={'>'} disabled={page === lastPage} />
        <PageButton label={'>>'} disabled={page === lastPage} />
      </div>
    </div>
  )
}

type CTA =
  | 'Enrolled'
  | 'RecentPoints'
  | 'BitcoinConnect'
  | 'BitcoinWithdrawal'
  | 'EvmWithdrawal'
  | 'LinkDiscord'
  | 'LinkX'

function CardCarousel({
  cards,
  onChangeTab,
  onCallToAction
}: {
  cards: [Card, ...Card[]]
  onChangeTab: (tab: Tab) => void
  onCallToAction: (type: CTA) => void
}) {
  const [index, setIndex] = useState(0)
  const [prevIndex, setPrevIndex] = useState(-1)
  const wallets = useWallets()

  useEffect(() => {
    const timer = window.setInterval(() => {
      setPrevIndex(index)
      setIndex(index === cards.length - 1 ? 0 : index + 1)
    }, 10000)
    return () => {
      window.clearInterval(timer)
    }
  })

  return (
    <div>
      <div className={'relative overflow-x-clip'}>
        {cards.map((card, ix) => {
          return (
            <div
              key={ix}
              className={classNames(
                'h-36 absolute text-white flex flex-row my-auto justify-center p-4',
                ix === index
                  ? 'animate-flyFromRight'
                  : ix === prevIndex
                    ? 'animate-flyToLeft'
                    : 'hidden'
              )}
            >
              {card.type === 'LinkDiscord' && (
                <>
                  <img
                    src={DiscordSvg}
                    alt={'discord'}
                    className="mx-4 my-auto size-12"
                  />
                  <div className="my-auto">
                    <span
                      className="cursor-pointer underline"
                      onClick={() => onCallToAction(card.type)}
                    >
                      Link
                    </span>{' '}
                    your Discord account to earn more points!
                  </div>
                </>
              )}
              {card.type === 'LinkX' && (
                <>
                  <img src={XSvg} alt={'x'} className="mx-4 my-auto size-12" />
                  <div className="my-auto">
                    <span
                      className="cursor-pointer underline"
                      onClick={() => onCallToAction(card.type)}
                    >
                      Link
                    </span>{' '}
                    your X account to earn more points!
                  </div>
                </>
              )}
              {card.type === 'Enrolled' && (
                <>
                  <img
                    src={CelebrationSvg}
                    alt={'celebrate'}
                    className="mx-4 my-auto size-12"
                  />
                  <div className="my-auto">
                    Congratulations, you&apos;re enrolled in the Testnet
                    Challenge! Now trade{' '}
                    <span
                      className="cursor-pointer underline"
                      onClick={() => onChangeTab('Swap')}
                    >
                      Swaps
                    </span>{' '}
                    and{' '}
                    <span
                      className="cursor-pointer underline"
                      onClick={() => onChangeTab('Limit')}
                    >
                      Limit Orders
                    </span>{' '}
                    to get ahead!
                  </div>
                </>
              )}
              {card.type === 'BitcoinConnect' && (
                <>
                  <img
                    src={BtcSvg}
                    alt={'BTC'}
                    className="mx-4 my-auto size-12"
                  />
                  <div className="my-auto">
                    <span
                      className="cursor-pointer underline"
                      onClick={() => wallets.connect('Bitcoin')}
                    >
                      Connect
                    </span>{' '}
                    your Bitcoin wallet and earn 500 funky bits!
                  </div>
                </>
              )}
              {card.type === 'RecentPoints' && (
                <>
                  <img
                    src={CelebrationSvg}
                    alt="celebrate"
                    className="mx-4 my-auto size-12"
                  />
                  <div className="my-auto">
                    Yes! You&apos;ve earned {card.points.toLocaleString()} funky
                    bits{' '}
                    {(card.pointType === 'DailyReward' ||
                      card.pointType === 'WeeklyReward' ||
                      card.pointType === 'OverallReward') && (
                      <>
                        for finishing
                        {card.pointType === 'DailyReward'
                          ? ' the day '
                          : card.pointType === 'WeeklyReward'
                            ? ' the week '
                            : card.pointType === 'OverallReward'
                              ? ' the Testnet Challenge '
                              : ''}
                        {card.category === 'Top1'
                          ? card.pointType === 'OverallReward'
                            ? ' as the undisputed Grandmaster of Funk! We are not worthy! '
                            : ' with the top PNL! Funk first! '
                          : card.category!.startsWith('Top')
                            ? ` with a PNL in the top ${parseInt(
                                card.category!.slice(3)
                              )} %! `
                            : card.category === 'Bottom1'
                              ? " with the absolute worst PNL! You're the funky caboose! "
                              : card.category!.startsWith('Bottom')
                                ? ` with a PNL in the bottom ${parseInt(
                                    card.category!.slice(6)
                                  )} %! `
                                : '. '}
                      </>
                    )}
                    {card.pointType === 'ReferralBonus' && (
                      <>from your referrals! Keep up the good work!</>
                    )}
                    {card.pointType === 'EvmWalletConnected' && (
                      <>for connecting your EVM wallet.</>
                    )}
                    {card.pointType === 'BitcoinWalletConnected' && (
                      <>for connecting your Bitcoin wallet.</>
                    )}
                  </div>
                </>
              )}
              {(card.type === 'BitcoinWithdrawal' ||
                card.type === 'EvmWithdrawal') && (
                <>
                  <img
                    src={LightBulbSvg}
                    alt="light bulb"
                    className="mx-4 my-auto size-12"
                  />
                  <div className="my-auto">
                    You can earn{' '}
                    {card.type === 'BitcoinWithdrawal' ? ' 500 ' : ' 250 '}{' '}
                    funky bits by{' '}
                    <span
                      className="cursor-pointer underline"
                      onClick={() => onCallToAction(card.type)}
                    >
                      withdrawing
                    </span>
                    {card.type === 'BitcoinWithdrawal'
                      ? ' some tBTC '
                      : ' to your wallet '}{' '}
                    and depositing it back.
                  </div>
                </>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

export function Leaderboard({
  pointsBalance,
  avatarUrl,
  nickName,
  inviteCode,
  wallets,
  balances,
  symbols,
  onChangeTab,
  onWithdrawal
}: {
  pointsBalance: Decimal
  avatarUrl?: string
  nickName?: string
  inviteCode: string
  wallets: Wallets
  balances: Balance[]
  symbols: TradingSymbols
  onChangeTab: (tab: Tab, widget?: Widget) => void
  onWithdrawal: (symbol: TradingSymbol) => void
}) {
  const queryClient = useQueryClient()

  const [dailyLeaderboardPage, setDailyLeaderboardPage] = useState(1)
  const [weeklyLeaderboardPage, setWeeklyLeaderboardPage] = useState(1)
  const [overallLeaderboardPage, setOverallLeaderboardPage] = useState(1)

  const dailyLeaderboardQuery = useQuery({
    queryKey: ['leaderboard', 'daily', dailyLeaderboardPage],
    queryFn: async () =>
      apiClient.testnetChallengeGetLeaderboard({
        params: { type: 'DailyPNL' },
        queries: { page: dailyLeaderboardPage }
      }),
    refetchInterval: 5000
  })

  const weeklyLeaderboardQuery = useQuery({
    queryKey: ['leaderboard', 'weekly', weeklyLeaderboardPage],
    queryFn: async () =>
      apiClient.testnetChallengeGetLeaderboard({
        params: { type: 'WeeklyPNL' },
        queries: { page: weeklyLeaderboardPage }
      }),
    refetchInterval: 5000
  })

  const overallLeaderboardQuery = useQuery({
    queryKey: ['leaderboard', 'overall', overallLeaderboardPage],
    queryFn: async () =>
      apiClient.testnetChallengeGetLeaderboard({
        params: { type: 'OverallPNL' },
        queries: { page: overallLeaderboardPage }
      }),
    refetchInterval: 5000
  })

  const cardQuery = useQuery({
    queryKey: cardsQueryKey,
    queryFn: apiClient.testnetChallengeGetCards
  })

  useEffect(() => {
    const authorizationHandler = () => {
      // refresh balance and cards
      queryClient.invalidateQueries({ queryKey: accountConfigQueryKey })
      queryClient.invalidateQueries({ queryKey: cardsQueryKey })
    }

    emitter.on('authorizedWallet', authorizationHandler)

    return () => {
      emitter.off('authorizedWallet', authorizationHandler)
    }
  }, [queryClient])

  const [newName, setNewName] = useState<string>()
  const [editMode, setEditMode] = useState<EditMode>('none')

  const displayName = useMemo(
    () =>
      newName ??
      nickName ??
      (wallets.primary && abbreviatedWalletAddress(wallets.primary)) ??
      '',
    [nickName, wallets, newName]
  )

  const [selectedAvatarFile, setSelectedAvatarFile] = useState<File>()
  const [avatarPreview, setAvatarPreview] = useState<string>()
  const [avatarDataUrl, setAvatarDataUrl] = useState<string>()
  const [avatarSaveError, setAvatarSaveError] = useState('')
  const [nicknameSaveError, setNicknameSaveError] = useState('')

  const startAccountLinking = useMutation({
    mutationFn: (accountType: UserLinkedAccountType) => {
      return apiClient.testnetChallengeStartAccountLinking(undefined, {
        params: { accountType }
      })
    },
    onSuccess: (response) => {
      window.open(response.authorizeUrl, '_self')
    },
    onError: (error, accountType) => {
      alert(`Unable to link your ${accountType} account`)
    }
  })

  const completeAccountLinking = useMutation({
    mutationFn: (params: {
      accountType: UserLinkedAccountType
      code: string
    }) => {
      return apiClient.testnetChallengeCompleteAccountLinking(
        {
          authorizationCode: params.code
        },
        { params: { accountType: params.accountType } }
      )
    },
    onSuccess: () => {
      window.location.href = '/'
    },
    onError: (error, params) => {
      alert(`Unable to link your ${params.accountType} account`)
      window.location.href = '/'
    }
  })

  useEffect(() => {
    if (window.location.pathname.includes('/discord-callback')) {
      const code = window.location.search.replace('?code=', '')
      if (code && !completeAccountLinking.isPending) {
        completeAccountLinking.mutate({ accountType: 'Discord', code })
      }
    }
  }, [completeAccountLinking])

  useEffect(() => {
    if (window.location.pathname.includes('/x-callback')) {
      const code = new URLSearchParams(window.location.search).get('code')
      if (code && !completeAccountLinking.isPending) {
        completeAccountLinking.mutate({ accountType: 'X', code })
      }
    }
  }, [completeAccountLinking])

  const onSelectIconFile = (e: ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) {
      setSelectedAvatarFile(undefined)
      return
    }

    setSelectedAvatarFile(e.target.files[0]!)
    setEditMode('icon')
    setAvatarSaveError('')
  }

  useEffect(() => {
    if (selectedAvatarFile) {
      const objectUrl = URL.createObjectURL(selectedAvatarFile)
      setAvatarPreview(objectUrl)
      // free memory when ever this component is unmounted
      return () => URL.revokeObjectURL(objectUrl)
    }
  }, [selectedAvatarFile])

  const MAX_DATA_URL_SIZE = 2 * 1024 * 1024

  const setNicknameMutation = useMutation({
    mutationFn: () => {
      return apiClient
        .testnetChallengeSetNickname({ name: newName ?? '' })
        .catch((error) => {
          if (
            error.response &&
            ApiErrorsSchema.safeParse(error.response.data).success
          ) {
            const parsedErrors = ApiErrorsSchema.parse(
              error.response.data
            ).errors
            setNicknameSaveError(parsedErrors[0].displayMessage)
          }
        })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: accountConfigQueryKey })
      queryClient.invalidateQueries({ queryKey: ['leaderboard'] })
      setNewName(undefined)
    },
    onError: () => {
      setNewName(undefined)
    }
  })

  const setAvatarUrlMutation = useMutation({
    mutationFn: ({ dataUrl }: { dataUrl: string }) =>
      apiClient.testnetChallengeSetAvatarUrl({ url: dataUrl ?? '' }),
    onSuccess: () => {
      setAvatarDataUrl(undefined)
      setSelectedAvatarFile(undefined)
      queryClient.invalidateQueries({ queryKey: accountConfigQueryKey })
      queryClient.invalidateQueries({ queryKey: ['leaderboard'] })
    },
    onError: () => {
      setAvatarPreview(undefined)
      setAvatarDataUrl(undefined)
      setSelectedAvatarFile(undefined)
      setAvatarSaveError('Unable to save icon')
    }
  })

  useEffect(() => {
    if (avatarPreview) {
      fetch(avatarPreview).then((r) => {
        const reader = new FileReader()
        r.blob().then((blob) => {
          reader.readAsDataURL(blob)
          reader.onloadend = () => {
            const data = reader.result!.toString()
            if (data.length < MAX_DATA_URL_SIZE) {
              setAvatarDataUrl(data)
            } else {
              setAvatarSaveError(
                `Too large: image as URL is ${data.length} bytes, max is ${MAX_DATA_URL_SIZE}`
              )
            }
          }
        })
      })
    }
  }, [avatarPreview, MAX_DATA_URL_SIZE])

  const [copyTooltipText, setCopyTooltipText] = useState('Copy to clipboard')
  const handleCopy = () => {
    setCopyTooltipText('Copied!')
    setTimeout(() => setCopyTooltipText('Copy to clipboard'), 2000)
  }

  function onCallToAction(type: CTA) {
    if (type === 'BitcoinWithdrawal') {
      if (wallets.isConnected('Bitcoin')) {
        onWithdrawal(symbols.native[0])
      } else {
        onChangeTab('Dashboard', 'Balances')
      }
    } else if (type === 'EvmWithdrawal') {
      const evmSymbols = symbols.erc20.filter((s) =>
        balances.some((b) => b.symbol === s.name && b.available > 0)
      )
      if (evmSymbols.length === 1 && wallets.isConnected('Evm')) {
        onWithdrawal(evmSymbols[0])
      } else {
        onChangeTab('Dashboard', 'Balances')
      }
    } else if (type == 'LinkDiscord') {
      startAccountLinking.mutate('Discord')
    } else if (type == 'LinkX') {
      startAccountLinking.mutate('X')
    }
  }

  return (
    <>
      <div className="flex flex-col gap-8 p-2">
        <div className="h-36 w-full rounded-lg bg-darkBluishGray8">
          <div className="flex flex-row">
            <div className="ml-2 mt-4 rounded-full bg-darkBluishGray10 p-4">
              <img
                src={avatarPreview ?? avatarUrl ?? AvatarSvg}
                alt="avatar"
                className="size-20"
              />
            </div>
            <div className="relative flex flex-col place-content-center p-2">
              <div className="my-1 text-2xl">
                {editMode === 'name' ? (
                  <input
                    className="rounded-xl border-none bg-darkBluishGray9 py-1"
                    placeholder={nickName ?? 'Nickname'}
                    value={newName ?? ''}
                    onChange={(e) => {
                      if (e.target.value.length <= 18) {
                        setNicknameSaveError('')
                        setNewName(e.target.value)
                      }
                    }}
                    onKeyDown={(e) => {
                      if (e.code === 'Enter') {
                        setNicknameMutation.mutate()
                        setEditMode('none')
                      }
                    }}
                    autoFocus={true}
                  />
                ) : (
                  <div className="mb-[2px] mt-[1px]">{displayName}</div>
                )}
              </div>
              <div className="text-sm">
                funky bits:{' '}
                <span className="text-statusOrange">
                  {pointsBalance.toFixed(0)}
                </span>
              </div>
              <div className="flex flex-row pt-2">
                <Button
                  caption={() => (
                    <div className="flex flex-row place-items-center px-1 py-2 text-sm">
                      <img src={EditSvg} alt={'edit'} className="mr-2 size-4" />
                      <div className="grow">
                        {editMode === 'name'
                          ? 'Save Name'
                          : editMode === 'icon'
                            ? 'Save Icon'
                            : 'Edit Name'}
                      </div>
                    </div>
                  )}
                  onClick={() => {
                    switch (editMode) {
                      case 'none':
                        setEditMode('name')
                        break
                      case 'name':
                        setNicknameMutation.mutate()
                        setEditMode('none')
                        break
                      case 'icon':
                        setAvatarUrlMutation.mutate({ dataUrl: avatarDataUrl! })
                        setEditMode('none')
                    }
                  }}
                  disabled={
                    (editMode === 'icon' && avatarDataUrl === undefined) ||
                    (editMode === 'name' &&
                      (newName === undefined || newName === ''))
                  }
                  style={'normal'}
                  width={'narrow'}
                  primary={true}
                />
                {editMode !== 'none' ? (
                  <Button
                    caption={() => (
                      <div className="flex flex-row place-items-center px-1 py-2 text-sm">
                        <div className="grow">Cancel</div>
                      </div>
                    )}
                    onClick={() => {
                      setEditMode('none')
                      setNewName(undefined)
                      setAvatarDataUrl(undefined)
                      setSelectedAvatarFile(undefined)
                      setAvatarPreview(undefined)
                    }}
                    disabled={false}
                    style={'normal'}
                    width={'narrow'}
                    primary={false}
                  />
                ) : (
                  <div className="relative cursor-pointer">
                    <Button
                      caption={() => (
                        <div className="flex flex-row place-items-center px-1 py-2 text-sm">
                          <img
                            src={CameraSvg}
                            alt={'edit'}
                            className="mr-2 size-4"
                          />
                          <div className="grow">Edit Icon</div>
                          <input
                            type="file"
                            accept="*.jpg,*.jpeg,*.svg,*.gif,*.png,image/jpeg,image/svg+xml,image/gif,image/png"
                            onChange={onSelectIconFile}
                            className="absolute left-0 w-full opacity-0"
                          />
                        </div>
                      )}
                      onClick={() => {}}
                      disabled={false}
                      style={'normal'}
                      width={'narrow'}
                      primary={editMode === 'none'}
                    />
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
        <div className="w-full rounded-lg bg-darkBluishGray8">
          {dailyLeaderboardQuery.data && (
            <Board
              type={'DailyPNL'}
              board={dailyLeaderboardQuery.data}
              goToPage={setDailyLeaderboardPage}
            />
          )}
        </div>
      </div>
      <div className="flex w-full flex-col gap-8 p-2">
        <div className="h-36 w-full rounded-lg bg-darkBluishGray10">
          {cardQuery.data && cardQuery.data.length > 0 && (
            <CardCarousel
              cards={[cardQuery.data[0], ...cardQuery.data.slice(1)]}
              onChangeTab={onChangeTab}
              onCallToAction={onCallToAction}
            />
          )}
        </div>
        <div className="w-full rounded-lg bg-darkBluishGray8">
          {weeklyLeaderboardQuery.data && (
            <Board
              type={'WeeklyPNL'}
              board={weeklyLeaderboardQuery.data}
              goToPage={setWeeklyLeaderboardPage}
            />
          )}
        </div>
      </div>
      <div className="flex flex-col gap-8 p-2">
        <div className="h-36 w-full rounded-lg bg-darkBluishGray8">
          <div className="flex flex-col space-y-4 p-3 text-center text-white">
            <div className="text-2xl">Refer Friends, Earn More!</div>
            <div className="text-sm">
              Share your unique referral link and earn 10% of the funky bits
              your referrals earn!
            </div>
            <div className="flex justify-center text-sm">
              <div className="pr-4">
                {webInviteBaseUrl}/{inviteCode}
              </div>
              <button
                data-tooltip-id="txCopyTooltip"
                data-tooltip-content={copyTooltipText}
              >
                <CopyToClipboard
                  text={`${webInviteBaseUrl}/${inviteCode}`}
                  onCopy={handleCopy}
                >
                  <img src={CopySvg} alt={'copy'} className="size-4" />
                </CopyToClipboard>
                <Tooltip id="txCopyTooltip" place="top" delayShow={200} />
              </button>
            </div>
          </div>
        </div>
        <div className="w-full rounded-lg bg-darkBluishGray8">
          {overallLeaderboardQuery.data && (
            <Board
              type={'OverallPNL'}
              board={overallLeaderboardQuery.data}
              goToPage={setOverallLeaderboardPage}
            />
          )}
        </div>
      </div>
      {avatarSaveError && (
        <Modal
          isOpen={avatarSaveError !== ''}
          title={'Unable to save image'}
          onClosed={() => {}}
          close={() => setAvatarSaveError('')}
        >
          <div className="flex flex-col place-items-center">
            <div className="text-statusRed">{avatarSaveError}</div>
            <button
              className="mt-4 w-fit bg-darkBluishGray8 px-4 text-white"
              onClick={() => setAvatarSaveError('')}
            >
              Ok
            </button>
          </div>
        </Modal>
      )}
      {nicknameSaveError && (
        <Modal
          isOpen={nicknameSaveError !== ''}
          title={'Unable to save nickname'}
          onClosed={() => {}}
          close={() => setNicknameSaveError('')}
        >
          <div className="flex flex-col place-items-center">
            <div className="text-statusRed">{nicknameSaveError}</div>
            <button
              className="mt-4 w-fit bg-darkBluishGray8 px-4 text-white"
              onClick={() => setNicknameSaveError('')}
            >
              Ok
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}
