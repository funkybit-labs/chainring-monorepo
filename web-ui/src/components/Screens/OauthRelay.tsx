import Spinner from 'components/common/Spinner'
import React, { useEffect } from 'react'
import { useMutation } from '@tanstack/react-query'
import { oauthRelayApiClient, UserLinkedAccountType } from 'apiClient'
import { clearOAuthRelayAuthTokenAndFlow } from 'contexts/oauthRelayAuth'

export type OAuthRelayFlow = 'discord' | 'x'

const discordGuildId = import.meta.env.ENV_DISCORD_FUNKYBIT_GUILD_ID

function goToDiscord() {
  window.location.replace(`https://discord.com/channels/${discordGuildId}`)
}

function goToX() {
  window.location.replace(`https://x.com/funkybit_fun`)
}

function complete(message: string, flow: OAuthRelayFlow) {
  clearOAuthRelayAuthTokenAndFlow()
  alert(message)
  switch (flow) {
    case 'discord':
      goToDiscord()
      break
    case 'x':
      goToX()
      break
  }
}

let ranFlow = false

export default function OauthRelay({ flow }: { flow: OAuthRelayFlow }) {
  const startAccountLinking = useMutation({
    mutationFn: (accountType: UserLinkedAccountType) => {
      return oauthRelayApiClient.oauthRelayStartAccountLinking(undefined, {
        params: { accountType }
      })
    },
    onSuccess: (response) => {
      window.open(response.authorizeUrl, '_self')
    },
    onError: () => {
      complete('Unable to start the authorization flow', flow)
    }
  })

  const completeDiscordLinking = useMutation({
    mutationFn: (code: string) => {
      return oauthRelayApiClient.oauthRelayCompleteAccountLinking(
        { authorizationCode: code },
        {
          params: { accountType: 'Discord' }
        }
      )
    },
    onSuccess: () =>
      complete(
        'Congratulations, you are now following funkybit on Discord! Return to the Telegram mini-app to claim your reward.',
        flow
      ),
    onError: () => complete('Unable to link your discord account', flow)
  })

  const completeXLinking = useMutation({
    mutationFn: (code: string) => {
      return oauthRelayApiClient.oauthRelayCompleteAccountLinking(
        { authorizationCode: code },
        {
          params: { accountType: 'X' }
        }
      )
    },
    onSuccess: () =>
      complete(
        'Congratulations, you are now following funkybit on X! Return to the Telegram mini-app to claim your reward.',
        flow
      ),
    onError: () => complete('Unable to link your X account', flow)
  })

  useEffect(() => {
    if (!ranFlow) {
      switch (flow) {
        case 'discord':
          if (window.location.pathname.includes('/discord-callback')) {
            const query = new URLSearchParams(window.location.search)
            const code = query.get('code')
            if (code) {
              completeDiscordLinking.mutate(code)
            } else {
              complete('Unable to link your discord account', flow)
            }
          } else {
            startAccountLinking.mutate('Discord')
          }
          break
        case 'x':
          if (window.location.pathname.includes('/x-callback')) {
            const query = new URLSearchParams(window.location.search)
            const code = query.get('code')
            if (code) {
              completeXLinking.mutate(code)
            } else {
              complete('Unable to link your X account', flow)
            }
          } else {
            startAccountLinking.mutate('X')
          }
      }
      ranFlow = true
    }
  }, [flow, completeDiscordLinking, completeXLinking, startAccountLinking])

  return (
    <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
      <Spinner />
    </div>
  )
}
