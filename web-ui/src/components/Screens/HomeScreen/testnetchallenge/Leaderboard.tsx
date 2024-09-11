import AvatarSvg from 'assets/avatar.svg'
import { shortenedWalletAddress, classNames } from 'utils'
import { Button } from 'components/common/Button'
import EditSvg from 'assets/Edit.svg'
import CameraSvg from 'assets/camera.svg'
import React, {
  ChangeEvent,
  Fragment,
  useEffect,
  useMemo,
  useState
} from 'react'
import { Wallets } from 'contexts/walletProvider'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  apiClient,
  ApiErrorsSchema,
  LeaderboardType,
  Leaderboard as LB
} from 'apiClient'
import { Modal } from 'components/common/Modal'

type EditMode = 'none' | 'name' | 'icon'

const rowsPerPage = 20

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

export function Leaderboard({
  avatarUrl,
  nickName,
  wallet
}: {
  avatarUrl?: string
  nickName?: string
  wallet: Wallets
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

  const [newName, setNewName] = useState<string>()
  const [editMode, setEditMode] = useState<EditMode>('none')

  const displayName = useMemo(
    () =>
      newName ??
      nickName ??
      (wallet.primary && shortenedWalletAddress(wallet.primary)) ??
      '',
    [nickName, wallet, newName]
  )

  const [selectedAvatarFile, setSelectedAvatarFile] = useState<File>()
  const [avatarPreview, setAvatarPreview] = useState<string>()
  const [avatarDataUrl, setAvatarDataUrl] = useState<string>()
  const [avatarSaveError, setAvatarSaveError] = useState('')
  const [nicknameSaveError, setNicknameSaveError] = useState('')

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
      queryClient.invalidateQueries({ queryKey: ['accountConfiguration'] })
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
      queryClient.invalidateQueries({ queryKey: ['accountConfiguration'] })
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
                funky bits: <span className="text-statusOrange">23,500</span>
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
      <div className="flex flex-col gap-8 p-2">
        <div className="h-36 w-full rounded-lg bg-darkBluishGray10"></div>
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
        <div className="h-36 w-full rounded-lg bg-darkBluishGray8"></div>
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
