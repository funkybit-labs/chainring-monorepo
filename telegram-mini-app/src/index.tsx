import { createRoot } from 'react-dom/client'
import 'tailwindcss/tailwind.css'
import React, { useEffect } from 'react'
import { useExpand, useShowPopup } from '@vkruglikov/react-telegram-web-app'
import { Button } from 'components/common/Button'

const container = document.getElementById('root') as HTMLDivElement
const root = createRoot(container)

const App = () => {
  const showPopup = useShowPopup()
  const [_, expand] = useExpand()

  useEffect(() => {
    expand()
  }, [])

  const showPopupOnClick = async () => {
    await showPopup({ title: 'Hello', message: 'World' })
  }

  return (
    <div className="flex items-center justify-center min-h-screen bg-darkBluishGray10">
      <Button
        caption={() => 'Test'}
        disabled={false}
        onClick={showPopupOnClick}
        style="normal"
        width="normal"
      />
    </div>
  )
}

root.render(<App />)
