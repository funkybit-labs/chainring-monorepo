import { FriendsSvg, GoalsSvg, HomeSvg } from 'components/icons'
import HomeTab from 'components/MainScreen/HomeTab'
import GoalsTab from 'components/MainScreen/GoalsTab'
import FriendsTab from 'components/MainScreen/FriendsTab'
import { ReactElement, useState } from 'react'
import { classNames } from 'utils'
import { User } from 'apiClient'
import { Alert } from 'components/EntryPoint'

type TabId = 'home' | 'goals' | 'friends'

export default function MainScreen({
  user,
  dismissedAlerts,
  dismissAlert
}: {
  user: User
  dismissedAlerts: Alert[]
  dismissAlert: (a: Alert) => void
}) {
  const [activeTab, setActiveTab] = useState<TabId>('home')

  return (
    <div className="h-full min-h-screen bg-mediumBlue pb-14">
      {(() => {
        switch (activeTab) {
          case 'home':
            return (
              <HomeTab
                user={user}
                dismissedAlerts={dismissedAlerts}
                dismissAlert={dismissAlert}
              />
            )
          case 'goals':
            return <GoalsTab user={user} />
          case 'friends':
            return <FriendsTab user={user} />
        }
      })()}
      <div className="fixed bottom-0 left-0 z-20 flex h-16 w-full justify-between rounded-t-3xl bg-darkBlue px-4 py-3 text-white">
        <TabButton
          title="Home"
          icon={<HomeSvg />}
          active={activeTab == 'home'}
          onClick={() => setActiveTab('home')}
        />
        <TabButton
          title="Goals"
          icon={<GoalsSvg />}
          active={activeTab == 'goals'}
          onClick={() => setActiveTab('goals')}
        />
        <TabButton
          title="Friends"
          icon={<FriendsSvg />}
          active={activeTab == 'friends'}
          onClick={() => setActiveTab('friends')}
        />
      </div>
    </div>
  )
}

export function TabButton({
  title,
  icon,
  active,
  onClick
}: {
  title: string
  icon: ReactElement
  active: boolean
  onClick: () => void
}) {
  return (
    <div
      className={classNames(
        'flex gap-2 items-center p-4 cursor-pointer rounded-xl text-xs',
        active
          ? 'text-primary4 bg-brightOrange bg-opacity-10'
          : 'border-transparent'
      )}
      onClick={onClick}
    >
      {icon}
      {title}
    </div>
  )
}
