import 'tailwindcss/tailwind.css'
import React, { useState } from 'react'
import LogoVSvg from 'assets/logo-v.svg'
import MillisecondsSvg from 'assets/milliseconds.svg'
import MainScreen from 'components/MainScreen'
import Spinner from 'components/common/Spinner'
import { apiClient, ApiErrorsSchema, userQueryKey } from 'apiClient'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { isErrorFromAlias } from '@zodios/core'
import { Button } from 'components/common/Button'

export default function EntryPoint() {
  const queryClient = useQueryClient()
  const [showInviteError, setShowInviteError] = useState(false)
  const [inviteCode, setInviteCode] = useState<string | null>(null)

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

  if (userQuery.isPending) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Spinner />
      </div>
    )
  } else if (userQuery.isSuccess) {
    if (userQuery.data === null) {
      return (
        <div className="flex h-screen flex-col justify-center gap-12">
          {showInviteError && (
            <div className="fixed inset-0 flex flex-col items-center justify-center bg-black/75 p-6">
              <div className="w-full max-w-lg rounded-lg bg-darkBluishGray10 p-6 shadow-lg">
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
            <img src={LogoVSvg} />
            <img src={MillisecondsSvg} className="my-8 mr-8" />
            <div className="mx-8 text-center text-3xl font-semibold text-white">
              When every millisecond counts
            </div>
          </div>
          <div className="mb-6 flex flex-col px-8">
            <div className="mb-4 text-center text-darkBluishGray2">
              Join our community and earn CR Points!
            </div>
            <Button
              caption={() => (
                <div className="py-3 text-lg font-semibold">
                  Lets get started
                </div>
              )}
              disabled={signUpMutation.isPending}
              onClick={() => {
                const query = new URLSearchParams(window.location.search)
                const code = query.get('tgWebAppStartParam')
                setInviteCode(code)
                signUpMutation.mutate()
              }}
            />
          </div>
        </div>
      )
    } else {
      return <MainScreen user={userQuery.data} />
    }
  } else if (userQuery.isError) {
    return (
      <div className="flex h-screen items-center justify-center text-white">
        Something went wrong, please try again later
      </div>
    )
  }
}
