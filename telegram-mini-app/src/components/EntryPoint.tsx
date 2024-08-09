import 'tailwindcss/tailwind.css'
import React, { useEffect, useState } from 'react'
import logo from 'assets/funkybit-orange-logo.png'
import logoWords from 'assets/logo-words.png'
import MainScreen from 'components/MainScreen'
import Spinner from 'components/common/Spinner'
import { apiClient, ApiErrorsSchema, userQueryKey } from 'apiClient'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { isErrorFromAlias } from '@zodios/core'
import { Button } from 'components/common/Button'

export type Alert = 'checkin' | 'milestone'

type SplashAnimationStep =
  | 'none'
  | 'spin'
  | 'words'
  | 'signup'
  | 'completing'
  | 'complete'

export default function EntryPoint() {
  const queryClient = useQueryClient()
  const [showInviteError, setShowInviteError] = useState(false)
  const [inviteCode, setInviteCode] = useState<string | null>(null)
  const [dismissedAlerts, setDismissedAlerts] = useState<Alert[]>([])
  const [animationStep, setAnimationStep] =
    useState<SplashAnimationStep>('none')

  const userQuery = useQuery({
    queryKey: userQueryKey,
    retry: false,
    queryFn: async () =>
      apiClient.getUser().catch((error) => {
        if (
          isErrorFromAlias(apiClient.api, 'getUser', error) &&
          error.response.data.errors[0].reason === 'SignupRequired'
        ) {
          return null
        } else {
          throw error
        }
      })
  })

  const signUpMutation = useMutation({
    mutationFn: async () => {
      return apiClient.signUp({ inviteCode: inviteCode }).catch((error) => {
        if (
          error.response &&
          ApiErrorsSchema.safeParse(error.response.data).success
        ) {
          const parsedErrors = ApiErrorsSchema.parse(error.response.data).errors
          if (parsedErrors.some((err) => err.reason === 'InvalidInviteCode')) {
            setShowInviteError(true)
          } else {
            alert(parsedErrors.map((err) => err.displayMessage).join(', '))
          }
        } else {
          alert('An unexpected error occurred')
        }
      })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: userQueryKey }),
    onError: () => queryClient.invalidateQueries({ queryKey: userQueryKey })
  })

  const handleProceedWithoutInvite = () => {
    setInviteCode(null)
    setShowInviteError(false)
    signUpMutation.mutate()
  }

  useEffect(() => {
    if (
      !showInviteError &&
      animationStep === 'none' &&
      userQuery.isSuccess &&
      userQuery.data === null
    ) {
      setAnimationStep('spin')
      window.setTimeout(() => {
        setAnimationStep('words')
        window.setTimeout(() => {
          setAnimationStep('signup')
        }, 3000)
      }, 2000)
    }
  }, [userQuery, animationStep, showInviteError])

  useEffect(() => {
    if (animationStep === 'signup') {
      setAnimationStep('completing')
      const query = new URLSearchParams(window.location.search)
      const code = query.get('tgWebAppStartParam')
      setInviteCode(code)
    } else if (animationStep === 'completing') {
      setAnimationStep('complete')
      signUpMutation.mutate()
    }
  }, [animationStep, signUpMutation])

  if (userQuery.isPending) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Spinner />
      </div>
    )
  } else if (userQuery.isSuccess) {
    if (userQuery.data === null) {
      return (
        <div className="flex h-screen flex-col justify-center gap-12 bg-mediumBlue">
          {showInviteError && (
            <div className="fixed inset-0 flex flex-col items-center justify-center bg-black/75 p-6">
              <div className="w-full max-w-lg rounded-lg bg-mediumBlue p-6 shadow-lg">
                <div className="mb-4 flex flex-col text-lg font-semibold text-white">
                  <div className="mb-1">Invalid invite code.</div>
                  <div>
                    Please contact the person you&apos;ve got the link from or
                    proceed without an invite code.
                  </div>
                </div>
                <div className="flex flex-wrap justify-end gap-4">
                  <Button
                    caption={() => 'Close'}
                    onClick={() => setShowInviteError(false)}
                  />
                  <Button
                    caption={() => 'Proceed'}
                    onClick={handleProceedWithoutInvite}
                  />
                </div>
              </div>
            </div>
          )}
          <div className="flex flex-col items-center justify-center">
            {animationStep === 'spin' && (
              <img
                className="size-16 origin-[28px_32px] animate-spin"
                src={logo}
                alt="funkybit"
              />
            )}
            {animationStep === 'words' && (
              <div>
                <img className="inline size-16" src={logo} alt="funkybit" />
                <img
                  className="inline h-8 animate-wordArtSlide"
                  src={logoWords}
                  alt="funkybit"
                />
              </div>
            )}
            {(animationStep === 'signup' ||
              animationStep === 'completing' ||
              animationStep === 'complete') && (
              <div>
                <img className="inline size-16" src={logo} alt="funkybit" />
                <img className="inline h-8" src={logoWords} alt="funkybit" />
              </div>
            )}
          </div>
        </div>
      )
    } else {
      return (
        <MainScreen
          user={userQuery.data}
          dismissedAlerts={dismissedAlerts}
          dismissAlert={(a) => {
            setDismissedAlerts([...dismissedAlerts, a])
          }}
        />
      )
    }
  } else if (userQuery.isError) {
    return (
      <div className="flex h-screen items-center justify-center bg-mediumBlue text-white">
        Something went wrong, please try again later
      </div>
    )
  }
}
