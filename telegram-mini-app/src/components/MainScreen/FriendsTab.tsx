import CRSvg from 'assets/cr.svg'
import { FriendsSvg } from 'components/icons'
import { Button } from 'components/common/Button'

export default function FriendsTab() {
  return (
    <div className="flex h-full flex-col justify-between">
      <div className="px-4">
        <div className="mb-4 flex justify-center pt-4 text-white">
          <FriendsSvg className="size-[60px]" />
        </div>
        <div className="flex flex-col items-center gap-2">
          <div className="flex gap-2">
            <div className="font-bold text-white">10000 CR</div>
            <div className="text-darkBluishGray2">
              for the registered friend
            </div>
          </div>
          <div className="mb-4 flex gap-2">
            <div className="font-bold text-white">10%</div>
            <div className="text-darkBluishGray2">from referral income</div>
          </div>
        </div>
      </div>
      <div className="flex flex-col items-center justify-center">
        <div className="flex items-center gap-4 rounded-lg bg-darkBluishGray9 px-5 py-4 text-2xl font-bold text-white">
          <img src={CRSvg} className="size-[40px]" />
          1,019,012.063 CR
        </div>
        <div className="mt-2 text-center text-sm text-darkBluishGray2">
          Accumulated from referral link
        </div>
      </div>
      <div className="mx-8 mb-4 flex flex-col">
        <div className="mb-4 text-center text-darkBluishGray2">
          Invite a friend and get referral bonus
        </div>
        <Button
          caption={() => (
            <div className="py-3 text-lg font-semibold">Invite a Friend</div>
          )}
          onClick={() => {}}
        />
      </div>
    </div>
  )
}
