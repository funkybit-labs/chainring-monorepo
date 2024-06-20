import { FriendsSvg, GoalsSvg, HomeSvg } from 'components/icons'
import HomeTab from 'components/MainScreen/HomeTab'
import GoalsTab from 'components/MainScreen/GoalsTab'
import FriendsTab from 'components/MainScreen/FriendsTab'
import { ReactElement, useState } from 'react'
import { classNames } from 'utils'

type TabId = 'home' | 'goals' | 'friends'

export default function MainScreen() {
  const [activeTab, setActiveTab] = useState<TabId>('home')

  return (
    <div className="h-screen pb-14">
      {(() => {
        switch (activeTab) {
          case 'home':
            return <HomeTab />
          case 'goals':
            return <GoalsTab />
          case 'friends':
            return <FriendsTab />
        }
      })()}
      <div className="fixed bottom-0 left-0 flex h-12 w-full justify-between bg-darkBluishGray10 px-4 py-3 text-white">
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
        'flex gap-2 items-center px-4 pb-1.5 border-b-2 cursor-pointer',
        active ? 'text-primary4 border-primary4' : 'border-transparent'
      )}
      onClick={onClick}
    >
      {icon}
      {title}
    </div>
  )
}
