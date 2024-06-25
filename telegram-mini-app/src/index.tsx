import { createRoot } from 'react-dom/client'
import 'tailwindcss/tailwind.css'
import React from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import EntryPoint from 'components/EntryPoint'

const container = document.getElementById('root') as HTMLDivElement
const root = createRoot(container)

const queryClient = new QueryClient()

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <EntryPoint />
    </QueryClientProvider>
  )
}

root.render(<App />)
