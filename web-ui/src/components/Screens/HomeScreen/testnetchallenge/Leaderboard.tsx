import AvatarSvg from 'assets/avatar.svg'
import { bitcoinAddressDisplay, evmAddressDisplay } from 'utils'
import { Button } from 'components/common/Button'
import EditSvg from 'assets/Edit.svg'
import CameraSvg from 'assets/camera.svg'
import React, { ChangeEvent, useEffect, useMemo, useState } from 'react'
import { Wallet } from 'contexts/walletProvider'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiClient, ApiErrorsSchema } from 'apiClient'
import { Modal } from 'components/common/Modal'

type EditMode = 'none' | 'name' | 'icon'

export function Leaderboard({
  avatarUrl,
  nickName,
  wallet
}: {
  avatarUrl?: string
  nickName?: string
  wallet: Wallet
}) {
  const queryClient = useQueryClient()

  const [newName, setNewName] = useState<string>()
  const [editMode, setEditMode] = useState<EditMode>('none')

  const displayName = useMemo(
    () =>
      nickName ??
      (wallet.primaryCategory === 'evm'
        ? evmAddressDisplay(wallet.primaryAddress ?? '')
        : bitcoinAddressDisplay(wallet.primaryAddress)),
    [nickName, wallet]
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
      setAvatarPreview(undefined)
      setAvatarDataUrl(undefined)
      setSelectedAvatarFile(undefined)
      queryClient.invalidateQueries({ queryKey: ['accountConfiguration'] })
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
                      setNicknameSaveError('')
                      setNewName(e.target.value)
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
        <div className="w-full grow rounded-lg bg-darkBluishGray8"></div>
      </div>
      <div className="flex flex-col gap-8 p-2">
        <div className="h-36 w-full rounded-lg bg-darkBluishGray10"></div>
        <div className="w-full grow rounded-lg bg-darkBluishGray8"></div>
      </div>
      <div className="flex flex-col gap-8 p-2">
        <div className="h-36 w-full rounded-lg bg-darkBluishGray8"></div>
        <div className="w-full grow rounded-lg bg-darkBluishGray8"></div>
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
