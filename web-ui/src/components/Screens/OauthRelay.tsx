import Spinner from 'components/common/Spinner'
import React, { useEffect } from 'react'
import { useMutation } from '@tanstack/react-query'
import { oauthRelayApiClient } from 'apiClient'
import { clearOAuthRelayAuthTokenAndFlow } from 'contexts/oauthRelayAuth'

export type OAuthRelayFlow = 'discord'

const discordClientId = import.meta.env.ENV_DISCORD_CLIENT_ID
const discordCallback = import.meta.env.ENV_WEB_URL + '/discord-callback'
const discordGuildId = import.meta.env.ENV_DISCORD_FUNKYBIT_GUILD_ID

function goToDiscord() {
  window.location.replace(`https://discord.com/channels/${discordGuildId}`)
}

function complete(message: string, flow: OAuthRelayFlow) {
  clearOAuthRelayAuthTokenAndFlow()
  alert(message)
  switch (flow) {
    case 'discord':
      goToDiscord()
  }
}

let ranFlow = false

export default function OauthRelay({ flow }: { flow: OAuthRelayFlow }) {
  const completeDiscordLinking = useMutation({
    mutationFn: (code: string) => {
      return oauthRelayApiClient.oauthRelayCompleteDiscordLinking(undefined, {
        params: { code }
      })
    },
    onSuccess: () =>
      complete(
        'Congratulations, you are now following funkybit on Discord! Return to the Telegram mini-app to claim your reward.',
        flow
      ),
    onError: () => complete('Unable to link your discord account', flow)
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
            window.open(
              `https://discord.com/api/oauth2/authorize?client_id=${discordClientId}&redirect_uri=${encodeURIComponent(
                discordCallback
              )}&response_type=code&scope=guilds.join+identify`,
              '_self'
            )
          }
      }
      ranFlow = true
    }
  }, [flow, completeDiscordLinking])

  return (
    <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
      <Spinner />
    </div>
  )
}
