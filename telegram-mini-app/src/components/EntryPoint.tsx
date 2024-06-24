import 'tailwindcss/tailwind.css'
import React from 'react'
import LogoVSvg from 'assets/logo-v.svg'
import MillisecondsSvg from 'assets/milliseconds.svg'
import MainScreen from 'components/MainScreen'
import Spinner from 'components/common/Spinner'
import { apiClient, userQueryKey } from 'apiClient'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { isErrorFromAlias } from '@zodios/core'
import { Button } from 'components/common/Button'

export default function EntryPoint() {
  const queryClient = useQueryClient()

  const userQuery = useQuery({
    queryKey: userQueryKey,
    retry: false,
    queryFn: async () =>
      apiClient.getUser().catch((error) => {
        if (
          isErrorFromAlias(apiClient.api, 'getUser', error) &&
          error.response.data.errors[0].reason == 'SignupRequired'
        ) {
          return null
        } else {
          throw error
        }
      })
  })

  const signUpMutation = useMutation({
    mutationFn: async () => apiClient.signUp(undefined),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: userQueryKey }),
    onError: () => {
      alert('Something went wrong')
    }
  })

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
              onClick={signUpMutation.mutate}
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
