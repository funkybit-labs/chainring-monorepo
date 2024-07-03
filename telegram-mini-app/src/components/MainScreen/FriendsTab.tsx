import CRSvg from 'assets/cr.svg'
import { FriendsSvg } from 'components/icons'
import { Button } from 'components/common/Button'
import { User } from 'apiClient'
import { CRView } from 'components/common/CRView'
import React from 'react'
import { useWebApp } from '@vkruglikov/react-telegram-web-app'

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

  return (
    <div className="flex h-full select-none flex-col justify-between">
      <div className="p-4">
        <div className="mb-8 flex justify-center pt-4 text-white">
          <FriendsSvg className="size-[60px]" />
        </div>
        <div className="flex flex-col items-center gap-2">
          <div className="flex items-center gap-2">
            <div className="text-lg font-bold text-white">{user.invites}</div>
            <div className="text-darkBluishGray2">
              Invite{user.invites === 1 ? '' : 's'} Left
            </div>
          </div>
          <div className="flex items-center gap-2">
            <div className="text-lg font-bold text-white">10 %</div>
            <div className="text-darkBluishGray2">of the points they earn</div>
          </div>
          <div className="mb-4 flex items-center gap-2">
            <div className="text-lg font-bold text-white">1%</div>
            <div className="text-darkBluishGray2">
              of the points their invites earn
            </div>
          </div>
        </div>
      </div>
      <div className="flex flex-col items-center justify-center">
        <div className="mx-auto mt-2 flex w-4/5 items-center justify-center gap-4 rounded-lg bg-darkBluishGray9 px-5 py-4 text-2xl font-bold text-white">
          <img src={CRSvg} className="size-10" alt="CR icon" />
          <CRView amount={user.referralBalance} />
        </div>
        <div className="mt-2 text-center text-sm text-darkBluishGray2">
          Earned through invites
        </div>
      </div>
      <div className="mx-8 mb-4 flex flex-col">
        <div className="mb-4 text-center text-darkBluishGray2">
          Invite a friend and earn more points!
        </div>
        {
          <Button
            className="mb-8 w-full border-2 !border-primary5 !bg-darkBluishGray10 p-1"
            caption={() => (
              <div className="py-3 text-lg font-semibold">Invite a Friend</div>
            )}
            onClick={handleInviteFriend}
          />
        }
      </div>
    </div>
  )
}
