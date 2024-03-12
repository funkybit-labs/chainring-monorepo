import { render, screen } from '@testing-library/react'

import App from './App'

describe('<App />', () => {
  it('should render the App', () => {
    const { container } = render(<App />)

    expect(
      screen.getByRole('heading', {
        name: /ChainRing/i,
        level: 1
      })
    ).toBeInTheDocument()

    expect(
      screen.getByText(/The first cross-chain DEX built on Bitcoin/i)
    ).toBeInTheDocument()

    expect(
      screen.getByRole('link', {
        name: 'Connect wallet'
      })
    ).toBeInTheDocument()

    expect(screen.getByRole('img')).toBeInTheDocument()

    expect(container.firstChild).toBeInTheDocument()
  })
})
