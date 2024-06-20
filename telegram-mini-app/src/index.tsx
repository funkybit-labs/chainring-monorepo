import { createRoot } from 'react-dom/client'
import 'tailwindcss/tailwind.css'
import React, { useState } from 'react'
import IntroScreen from 'components/IntroScreen'
import MainScreen from 'components/MainScreen'

const container = document.getElementById('root') as HTMLDivElement
const root = createRoot(container)

const App = () => {
  const [showIntro, setShowIntro] = useState(true)

  if (showIntro) {
    return <IntroScreen onStartButtonClick={() => setShowIntro(false)} />
  } else {
    return <MainScreen />
  }
}

root.render(<App />)
