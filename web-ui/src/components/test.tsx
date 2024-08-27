import { render, screen } from '@testing-library/react'

import App from 'components/App'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { WagmiProvider } from 'wagmi'
import { initializeWagmiConfig, wagmiConfig } from 'wagmiConfig'
import { WebSocket as MockWebSocket } from 'mock-socket'

// Create an interface that extends the original WebSocket interface
interface ExtendedWebSocket extends WebSocket {
  ping: () => void
}

// Create a new class that implements the ExtendedWebSocket interface
class CustomWebSocket extends MockWebSocket implements ExtendedWebSocket {
  // Add any missing methods here
  ping() {
    // Implement the ping method if needed
    console.log('Ping method called')
  }
}

describe('<App />', () => {
  global.WebSocket = CustomWebSocket
  it('should render the App', () => {
    initializeWagmiConfig().then(() => {
      const { container } = render(
        <WagmiProvider config={wagmiConfig}>
          <QueryClientProvider client={new QueryClient()}>
            <App />
          </QueryClientProvider>
        </WagmiProvider>
      )

      expect(screen.getAllByAltText('funkybit')[0]).toBeInTheDocument()

      expect(
        screen.getByRole('button', {
          name: 'Connect Wallet'
        })
      ).toBeInTheDocument()

      expect(container.firstChild).toBeInTheDocument()
    })
  })
})
