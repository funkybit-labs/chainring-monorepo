import GiftPng from 'assets/gift.png'
import CoinPng from 'assets/coin.png'
import FriendsPng from 'assets/friends.png'
import { Button } from 'components/common/Button'
import { User } from 'apiClient'
import React, { useMemo } from 'react'
import { useWebApp } from '@vkruglikov/react-telegram-web-app'
import { HeadSub, InfoPanel } from 'components/common/InfoPanel'
import { decimalAsInt } from 'utils/format'
import Logo from 'components/common/Logo'

const tgBotId = import.meta.env.ENV_TG_BOT_ID
const tgMiniAppId = import.meta.env.ENV_TG_APP_ID

export default function FriendsTab({ user }: { user: User }) {
  const telegramWebApp = useWebApp()

  const handleInviteFriend = () => {
    const inviteLink = `https://t.me/${tgBotId}/${tgMiniAppId}?startapp=${user.inviteCode}`
    const text = `Hey, join me on ChainRing!`

    const shareLike = `https://t.me/share/url?url=${inviteLink}&text=${text}`

    telegramWebApp.openTelegramLink(shareLike)
  }

  const hasReferralBalance = useMemo(() => {
    return user.referralBalance.gt(0)
  }, [user.referralBalance])

  return (
    <div className="relative flex select-none flex-col">
      <Logo />
      <div className="mx-6 flex flex-col">
        <div className="mt-2 text-left font-sans text-2xl font-semibold text-white">
          Friends
        </div>
        {hasReferralBalance && (
          <InfoPanel icon={CoinPng} rounded={'top'}>
            <HeadSub
              head={decimalAsInt(user.referralBalance)}
              sub="EARNED THROUGH INVITE"
            />
          </InfoPanel>
        )}
        <InfoPanel
          icon={GiftPng}
          rounded={hasReferralBalance ? 'none' : 'top'}
          style={hasReferralBalance ? 'standard' : 'large'}
        >
          <HeadSub
            head={user.invites.toString()}
            sub={user.invites === 1 ? 'INVITE LEFT' : 'INVITES LEFT'}
            style={hasReferralBalance ? 'standard' : 'equal'}
          />
        </InfoPanel>
        <div className="mx-auto mt-2 flex w-full justify-stretch rounded-t-3xl bg-darkBlue px-6 py-4">
          <div className="flex w-full items-center justify-start gap-4 font-medium text-white">
            <div className="flex w-full flex-col items-center gap-2">
              <img src={FriendsPng} alt="Invite Friends" className="w-16" />
              <div className="text-center text-xl text-white">
                Invite a friend &
                <br />
                earn more points!
              </div>
              <div className="text-center text-brightOrange">
                10% of the points they earn.
                <br />
                1% of the points their invites earn.
              </div>
              <Button
                className="!bg-brightOrange/70 px-4 py-1"
                caption={() => <div className="py-1">Invite a Friend</div>}
                onClick={handleInviteFriend}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
